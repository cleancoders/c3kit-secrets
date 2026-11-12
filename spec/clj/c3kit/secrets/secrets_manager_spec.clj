(ns c3kit.secrets.secrets-manager-spec
  (:require [c3kit.secrets :as secrets]
            [c3kit.secrets.secrets-manager :as sut]
            [speclj.core :refer :all])
  (:import (software.amazon.awssdk.services.secretsmanager SecretsManagerClient)
           (software.amazon.awssdk.services.secretsmanager.model GetSecretValueRequest
                                                                 GetSecretValueResponse
                                                                 ResourceNotFoundException
                                                                 SecretsManagerException)))

(defn ->fake-client [secrets requests closed]
  (proxy [SecretsManagerClient] []
    (serviceName [] "secretsmanager")
    (close [] (reset! closed true))
    (getSecretValue [^GetSecretValueRequest request]
      (let [id (.secretId request)]
        (swap! requests conj id)
        (if-let [value (get secrets id)]
          (-> (GetSecretValueResponse/builder) (.name id) (.secretString value) .build)
          (throw (-> (ResourceNotFoundException/builder) (.message "not found") .build)))))))

(def stored
  {"epic/staging/redis-host" "redis.staging"
   "epic/staging/s3-bucket"  "epic-bucket"
   "shared/staging/jwt-secret" "shh"})

(describe "Secrets Manager source"

  (with requests (atom []))
  (with closed (atom false))
  (with client (->fake-client stored @requests @closed))
  (with source (secrets/create {:impl :secrets-manager :client @client}))

  (it "names secrets scope/env/key, the legacy layout"
    (should= "epic/staging/redis-host" (sut/secret-name "staging" "epic" :redis-host)))

  (it "gets a value"
    (should= "redis.staging" (secrets/get-value @source "staging" "epic" :redis-host))
    (should= ["epic/staging/redis-host"] @@requests))

  (it "returns nil for a missing secret"
    (should-be-nil (secrets/get-value @source "staging" "epic" :missing)))

  (it "propagates other Secrets Manager errors"
    (let [failing (proxy [SecretsManagerClient] []
                    (serviceName [] "secretsmanager")
                    (close [])
                    (getSecretValue [_request]
                      (throw (-> (SecretsManagerException/builder) (.message "AccessDenied") .build))))
          s       (secrets/create {:impl :secrets-manager :client failing})]
      (should-throw SecretsManagerException (secrets/get-value s "staging" "epic" :redis-host))))

  (it "loads a scope one GetSecretValue per key, omitting missing keys"
    (should= {:redis-host "redis.staging" :s3-bucket "epic-bucket"}
             (secrets/load-all @source "staging" {"epic" [:redis-host :s3-bucket :missing]}))
    (should= #{"epic/staging/redis-host" "epic/staging/s3-bucket" "epic/staging/missing"}
             (set @@requests)))

  (it "does not close a client it was given"
    (secrets/close @source)
    (should= false @@closed))

  (it "closes a client it created"
    (with-redefs [sut/->client (fn [_spec] @client)]
      (secrets/close (secrets/create {:impl :secrets-manager}))
      (should= true @@closed)))

  (context "transition from Secrets Manager to SSM"

    (it "a chain reads SSM first and falls back to Secrets Manager"
      (let [chain (secrets/create {:impl    :chain
                                   :sources [{:impl :memory :values {"staging" {"epic" {:redis-host "from-ssm"}}}}
                                             {:impl :secrets-manager :client @client}]})]
        (should= {:redis-host "from-ssm" :s3-bucket "epic-bucket"}
                 (secrets/load-all chain "staging" {"epic" [:redis-host :s3-bucket]}))
        (should= ["epic/staging/s3-bucket"] @@requests)))))
