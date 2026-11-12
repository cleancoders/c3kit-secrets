(ns c3kit.secrets.ssm-spec
  (:require [c3kit.secrets :as secrets]
            [c3kit.secrets.ssm :as sut]
            [clojure.string :as str]
            [speclj.core :refer :all])
  (:import (software.amazon.awssdk.services.ssm SsmClient)
           (software.amazon.awssdk.services.ssm.model GetParameterRequest
                                                      GetParameterResponse
                                                      GetParametersByPathRequest
                                                      GetParametersByPathResponse
                                                      Parameter
                                                      ParameterNotFoundException
                                                      SsmException)))

(defn- ->parameter [n v]
  (-> (Parameter/builder) (.name n) (.value v) .build))

(defn ->fake-client
  "An SsmClient over a {name value} map. Pages GetParametersByPath results
   page-size at a time and records every request in `requests`."
  [params requests closed & [{:keys [page-size] :or {page-size 2}}]]
  (proxy [SsmClient] []
    (serviceName [] "ssm")
    (close [] (reset! closed true))
    (getParameter [^GetParameterRequest request]
      (swap! requests conj request)
      (if-let [value (get params (.name request))]
        (-> (GetParameterResponse/builder)
            (.parameter (->parameter (.name request) value))
            .build)
        (throw (-> (ParameterNotFoundException/builder) (.message "not found") .build))))
    (getParametersByPath [^GetParametersByPathRequest request]
      (swap! requests conj request)
      (let [path    (.path request)
            matches (->> (sort params)
                         (filter (fn [[n _]] (and (str/starts-with? n path)
                                                  (not (str/includes? (subs n (count path)) "/"))))))
            start   (if-let [t (.nextToken request)] (parse-long t) 0)
            page    (take page-size (drop start matches))
            more?   (< (+ start page-size) (count matches))]
        (-> (GetParametersByPathResponse/builder)
            (.parameters ^java.util.Collection (map (fn [[n v]] (->parameter n v)) page))
            (cond-> more? (.nextToken (str (+ start page-size))))
            .build)))))

(def params
  {"/staging/epic/redis-host"       "redis.staging"
   "/staging/epic/s3-bucket"        "epic-bucket"
   "/staging/epic/poker-auth-secret" "psst"
   "/staging/epic/nested/deeper"    "not-a-direct-child"
   "/staging/shared/jwt-secret"     "shh"
   "/production/epic/redis-host"    "redis.production"})

(describe "SSM source"

  (with requests (atom []))
  (with closed (atom false))
  (with client (->fake-client params @requests @closed))
  (with source (secrets/create {:impl :ssm :client @client}))

  (it "names parameters /env/scope/key"
    (should= "/staging/epic/redis-host" (sut/parameter-name "staging" "epic" :redis-host)))

  (it "gets a value with decryption"
    (should= "redis.staging" (secrets/get-value @source "staging" "epic" :redis-host))
    (let [^GetParameterRequest request (first @@requests)]
      (should= "/staging/epic/redis-host" (.name request))
      (should= true (.withDecryption request))))

  (it "returns nil for a missing parameter"
    (should-be-nil (secrets/get-value @source "staging" "epic" :missing)))

  (it "propagates other SSM errors"
    (let [failing (proxy [SsmClient] []
                    (serviceName [] "ssm")
                    (close [])
                    (getParameter [_request]
                      (throw (-> (SsmException/builder) (.message "AccessDenied") .build))))
          s       (secrets/create {:impl :ssm :client failing})]
      (should-throw SsmException (secrets/get-value s "staging" "epic" :redis-host))))

  (it "loads a scope with one decrypted, non-recursive path query per page"
    (should= {:redis-host "redis.staging" :s3-bucket "epic-bucket"}
             (secrets/load-all @source "staging" {"epic" [:redis-host :s3-bucket]}))
    (should (every? #(instance? GetParametersByPathRequest %) @@requests))
    (let [^GetParametersByPathRequest request (first @@requests)]
      (should= "/staging/epic/" (.path request))
      (should= true (.withDecryption request))
      (should= false (.recursive request))))

  (it "follows nextToken across pages"
    (should= {:poker-auth-secret "psst" :redis-host "redis.staging" :s3-bucket "epic-bucket"}
             (secrets/load-all @source "staging" {"epic" [:redis-host :s3-bucket :poker-auth-secret]}))
    (should= 2 (count @@requests)))

  (it "returns only the requested keys"
    (should= {:s3-bucket "epic-bucket"}
             (secrets/load-all @source "staging" {"epic" [:s3-bucket]})))

  (it "ignores parameters nested below the scope"
    (should-be-nil (:deeper (secrets/load-all @source "staging" {"epic" [:deeper]}))))

  (it "keeps environments apart"
    (should= {:redis-host "redis.production"}
             (secrets/load-all @source "production" {"epic" [:redis-host]})))

  (it "does not close a client it was given"
    (secrets/close @source)
    (should= false @@closed))

  (it "closes a client it created"
    (with-redefs [sut/->client (fn [_spec] @client)]
      (let [s (secrets/create {:impl :ssm :region "us-west-2"})]
        (secrets/close s)
        (should= true @@closed))))

  (it "builds its client for the configured region"
    (let [^SsmClient c (sut/->client {:region "us-west-2"})]
      (try
        (should= "us-west-2" (str (.region (.serviceClientConfiguration c))))
        (finally (.close c))))))
