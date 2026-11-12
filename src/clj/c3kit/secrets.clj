(ns c3kit.secrets
  "Loads secrets and configuration values from a pluggable source.

   AWS SSM Parameter Store (`:ssm`, see `c3kit.secrets.ssm`) is the primary
   source. Values are addressed by environment, scope and key — for example
   env \"staging\", scope \"epic\", key :redis-host — and each source maps that
   to its own naming scheme.

   Sources:
     :ssm             - SSM Parameter Store, /{env}/{scope}/{key}. The intended
                        end state. Require c3kit.secrets.ssm.
     :secrets-manager - AWS Secrets Manager, {scope}/{env}/{key}. TRANSITIONAL:
                        exists only to read secrets that have not yet moved to
                        SSM. Require c3kit.secrets.secrets-manager.
     :chain           - tries several sources in order; the first value found
                        wins. Use it during a migration: SSM first, Secrets
                        Manager second.
     :memory          - an in-memory map, for development and specs.

   Every source is java.io.Closeable, so `with-open` works."
  (:require [clojure.string :as str]))

(defprotocol Source
  (-get-value [this env scope k]
    "The value at env/scope/key, or nil when it does not exist. env and scope
     are strings; key is a keyword.")
  (-load-scope [this env scope ks]
    "A map of keyword -> value for the keys in ks that exist under env/scope.
     Keys that do not exist are omitted.")
  (-close [this]
    "Releases any resources, such as AWS clients, the source owns."))

(defmulti create
  "Creates a source from a spec map, dispatching on :impl."
  :impl)

(defmethod create :default [spec]
  ;; ex-data carries only :impl: a spec may hold values (e.g. :memory).
  (throw (ex-info (str "Unknown secrets source :impl " (pr-str (:impl spec))
                       ". Is its namespace required (e.g. c3kit.secrets.ssm)?")
                  {:impl (:impl spec)})))

(defn- ->str [x] (if (keyword? x) (name x) (str x)))
(defn- ->key [k] (keyword (->str k)))

(defn get-value
  "The value at env/scope/key, or nil when no such value exists."
  [source env scope k]
  (-get-value source (->str env) (->str scope) (->key k)))

(defn get-value!
  "Like get-value, but throws when the value does not exist."
  [source env scope k]
  (or (get-value source env scope k)
      (throw (ex-info (str "Missing secret " (->str scope) "/" (->str k) " in " (->str env))
                      {:env (->str env) :scope (->str scope) :key (->key k)}))))

(defn- normalize-request [scope->keys]
  (let [request (into {} (map (fn [[scope ks]] [(->str scope) (mapv ->key ks)])) scope->keys)]
    (doseq [[k scopes] (->> (for [[scope ks] request k ks] [k scope])
                            (group-by first))
            :when (< 1 (count (distinct (map second scopes))))]
      (throw (ex-info (str "Key " k " requested from more than one scope")
                      {:key k :scopes (vec (distinct (map second scopes)))})))
    request))

(defn load-all
  "Loads several keys from several scopes into one flat map.

   scope->keys maps each scope to the keys wanted from it:
     (load-all source \"staging\" {\"shared\" [:jwt-secret]
                                   \"epic\"   [:redis-host :s3-bucket]})
     => {:jwt-secret \"...\" :redis-host \"...\" :s3-bucket \"...\"}

   Keys that do not exist are omitted. A key requested from two scopes is
   ambiguous in the flat result and throws."
  [source env scope->keys]
  (let [env (->str env)]
    (reduce-kv (fn [result scope ks] (merge result (-load-scope source env scope ks)))
               {}
               (normalize-request scope->keys))))

(defn load-all!
  "Like load-all, but throws when any requested key does not exist. The error
   names every missing env/scope/key and never includes values."
  [source env scope->keys]
  (let [result  (load-all source env scope->keys)
        missing (for [[scope ks] (normalize-request scope->keys)
                      k ks
                      :when (not (contains? result k))]
                  {:scope scope :key k})]
    (when (seq missing)
      (throw (ex-info (str "Missing secrets in " (->str env) ": "
                           (->> missing (map #(str (:scope %) "/" (name (:key %)))) (str/join ", ")))
                      {:env (->str env) :missing (vec missing)})))
    result))

(defn close
  "Releases any resources the source owns."
  [source]
  (-close source))

;region Memory

(deftype MemorySource [values]
  Source
  (-get-value [_ env scope k] (get-in values [env scope k]))
  (-load-scope [_ env scope ks] (select-keys (get-in values [env scope]) ks))
  (-close [_])
  java.io.Closeable
  (close [_]))

(defn- normalize-values [values]
  (into {} (for [[env scopes] values]
             [(->str env) (into {} (for [[scope kvs] scopes]
                                     [(->str scope) (into {} (for [[k v] kvs] [(->key k) v]))]))])))

(defmethod create :memory [{:keys [values]}]
  (MemorySource. (normalize-values values)))

;endregion

;region Chain

(defn- close-all! [sources]
  (let [errors (reduce (fn [errors source]
                         (try (-close source) errors
                              (catch Exception e (conj errors e))))
                       []
                       sources)]
    (when-let [e (first errors)] (throw e))))

(deftype ChainSource [sources]
  Source
  (-get-value [_ env scope k]
    (some #(-get-value % env scope k) sources))
  (-load-scope [_ env scope ks]
    (loop [sources sources wanted (vec ks) found {}]
      (if (or (empty? wanted) (empty? sources))
        found
        (let [loaded (-load-scope (first sources) env scope wanted)]
          (recur (rest sources)
                 (vec (remove #(contains? loaded %) wanted))
                 (merge loaded found))))))
  (-close [_] (close-all! sources))
  java.io.Closeable
  (close [this] (-close this)))

(defn- ->source [spec-or-source]
  (if (satisfies? Source spec-or-source) spec-or-source (create spec-or-source)))

(defmethod create :chain [{:keys [sources]}]
  ;; If a later source fails to build, close the ones already built.
  (ChainSource. (reduce (fn [built spec]
                          (try (conj built (->source spec))
                               (catch Exception e
                                 (try (close-all! built) (catch Exception _))
                                 (throw e))))
                        []
                        sources)))

;endregion
