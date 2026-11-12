(ns c3kit.secrets-spec
  (:require [c3kit.secrets :as sut]
            [speclj.core :refer :all]))

(def values
  {"staging" {"shared" {:jwt-secret "shh" :jwt-cookie-name "cc-token"}
              "epic"   {:redis-host "redis.staging" :s3-bucket "epic-bucket"}}})

(defn ->memory [vs] (sut/create {:impl :memory :values vs}))

(defn ->closeable-memory
  "A memory source that records when it is closed."
  [vs closed]
  (let [inner (->memory vs)]
    (reify
      sut/Source
      (-get-value [_ env scope k] (sut/-get-value inner env scope k))
      (-load-scope [_ env scope ks] (sut/-load-scope inner env scope ks))
      (-close [_] (swap! closed conj vs)))))

(describe "Secrets"

  (context "create"

    (it "throws on an unknown impl without leaking the spec"
      (let [error (try (sut/create {:impl :bogus :values {"x" "secret-value"}})
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
        (should-not-be-nil error)
        (should-contain "Unknown secrets source :impl :bogus" (ex-message error))
        (should= {:impl :bogus} (ex-data error)))))

  (context "memory"

    (with source (->memory values))

    (it "gets a value"
      (should= "redis.staging" (sut/get-value @source "staging" "epic" :redis-host)))

    (it "accepts keyword or string env, scope and key"
      (should= "redis.staging" (sut/get-value @source :staging :epic "redis-host")))

    (it "returns nil for a missing value"
      (should-be-nil (sut/get-value @source "staging" "epic" :missing))
      (should-be-nil (sut/get-value @source "production" "epic" :redis-host)))

    (it "get-value! returns a present value"
      (should= "shh" (sut/get-value! @source "staging" "shared" :jwt-secret)))

    (it "get-value! throws on a missing value, naming it"
      (let [error (try (sut/get-value! @source "staging" "epic" :missing)
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
        (should-not-be-nil error)
        (should= {:env "staging" :scope "epic" :key :missing} (ex-data error))))

    (it "loads keys from several scopes into one flat keyword map"
      (should= {:jwt-secret "shh" :redis-host "redis.staging" :s3-bucket "epic-bucket"}
               (sut/load-all @source "staging" {"shared" [:jwt-secret]
                                                "epic"   ["redis-host" :s3-bucket]})))

    (it "load-all omits missing keys"
      (should= {:jwt-secret "shh"}
               (sut/load-all @source "staging" {"shared" [:jwt-secret :nope]})))

    (it "load-all! throws listing every missing key"
      (let [error (try (sut/load-all! @source "staging" {"shared" [:jwt-secret :nope]
                                                         "epic"   [:redis-host :gone]})
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
        (should-not-be-nil error)
        (should= "staging" (:env (ex-data error)))
        (should= #{{:scope "shared" :key :nope} {:scope "epic" :key :gone}}
                 (set (:missing (ex-data error))))))

    (it "load-all! never puts values in the error"
      (let [error (try (sut/load-all! @source "staging" {"shared" [:jwt-secret :nope]})
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
        (should-not-contain "shh" (pr-str (ex-data error)))
        (should-not-contain "shh" (ex-message error))))

    (it "load-all! returns the map when nothing is missing"
      (should= {:jwt-secret "shh"} (sut/load-all! @source "staging" {"shared" [:jwt-secret]})))

    (it "rejects the same key requested from two scopes"
      (should-throw clojure.lang.ExceptionInfo
                    (sut/load-all @source "staging" {"shared" [:redis-host] "epic" [:redis-host]})))

    (it "is closeable with with-open"
      (should= "shh" (with-open [s (->memory values)]
                       (sut/get-value s "staging" "shared" :jwt-secret)))))

  (context "chain"

    (with first-values {"staging" {"epic" {:redis-host "from-first"}}})
    (with second-values {"staging" {"epic" {:redis-host "from-second" :s3-bucket "bucket-second"}}})
    (with chain (sut/create {:impl    :chain
                             :sources [{:impl :memory :values @first-values}
                                       {:impl :memory :values @second-values}]}))

    (it "returns the first source's value"
      (should= "from-first" (sut/get-value @chain "staging" "epic" :redis-host)))

    (it "falls back to a later source for a value the first lacks"
      (should= "bucket-second" (sut/get-value @chain "staging" "epic" :s3-bucket)))

    (it "returns nil when no source has the value"
      (should-be-nil (sut/get-value @chain "staging" "epic" :missing)))

    (it "load-all merges sources, earlier sources winning"
      (should= {:redis-host "from-first" :s3-bucket "bucket-second"}
               (sut/load-all @chain "staging" {"epic" [:redis-host :s3-bucket]})))

    (it "only asks later sources for the keys still missing"
      (let [asked  (atom [])
            spy    (reify sut/Source
                     (-get-value [_ _env _scope _key] nil)
                     (-load-scope [_ _env _scope ks] (swap! asked conj (set ks)) {})
                     (-close [_]))
            c      (sut/create {:impl :chain :sources [{:impl :memory :values @first-values} spy]})]
        (sut/load-all c "staging" {"epic" [:redis-host :s3-bucket]})
        (should= [#{:s3-bucket}] @asked)))

    (it "does not ask later sources when every key was found"
      (let [asked (atom 0)
            spy   (reify sut/Source
                    (-get-value [_ _env _scope _key] (swap! asked inc) nil)
                    (-load-scope [_ _env _scope _ks] (swap! asked inc) {})
                    (-close [_]))
            c     (sut/create {:impl :chain :sources [{:impl :memory :values @first-values} spy]})]
        (sut/get-value c "staging" "epic" :redis-host)
        (sut/load-all c "staging" {"epic" [:redis-host]})
        (should= 0 @asked)))

    (it "accepts already-created sources"
      (let [c     (sut/create {:impl :chain :sources [(->memory @first-values)]})]
        (should= "from-first" (sut/get-value c "staging" "epic" :redis-host))))

    (it "closes every source"
      (let [closed (atom [])
            c      (sut/create {:impl    :chain
                                :sources [(->closeable-memory {"a" {}} closed)
                                          (->closeable-memory {"b" {}} closed)]})]
        (sut/close c)
        (should= [{"a" {}} {"b" {}}] @closed)))

    (it "closes the remaining sources even if one throws"
      (let [closed (atom [])
            bad    (reify sut/Source
                     (-get-value [_ _env _scope _key] nil)
                     (-load-scope [_ _env _scope _ks] {})
                     (-close [_] (throw (ex-info "boom" {}))))
            c      (sut/create {:impl :chain :sources [bad (->closeable-memory {"b" {}} closed)]})]
        (should-throw clojure.lang.ExceptionInfo "boom" (sut/close c))
        (should= [{"b" {}}] @closed)))))
