(ns c3kit.secrets.secrets-manager
  "AWS Secrets Manager source. TRANSITIONAL — deprecated on arrival.

   It exists only so apps can keep reading secrets that have not yet moved to
   SSM Parameter Store. Put it after :ssm in a :chain, move each value to SSM,
   then remove this source and delete the Secrets Manager secrets.

   Values live at {scope}/{env}/{key}, e.g. epic/staging/redis-host — the
   layout the c3kit apps used before c3kit.secrets. Only string secrets are
   read; a binary secret reads as missing.

   spec:
     {:impl   :secrets-manager
      :region \"us-west-2\"  ; optional; otherwise the SDK's default region chain
      :client sm-client}     ; optional SecretsManagerClient. A client passed in
                             ; is the caller's to close.

   Loading a scope makes one GetSecretValue call per key, so it needs only
   secretsmanager:GetSecretValue — the permission the apps already have."
  (:require [c3kit.secrets :as secrets])
  (:import (software.amazon.awssdk.regions Region)
           (software.amazon.awssdk.services.secretsmanager SecretsManagerClient SecretsManagerClientBuilder)
           (software.amazon.awssdk.services.secretsmanager.model GetSecretValueRequest
                                                                 ResourceNotFoundException)))

(defn secret-name
  "The Secrets Manager name for env/scope/key: {scope}/{env}/{key}."
  [env scope k]
  (str scope "/" env "/" (name k)))

(defn ->client
  "Builds a SecretsManagerClient, using :region when given."
  ^SecretsManagerClient [{:keys [region]}]
  (let [^SecretsManagerClientBuilder builder (SecretsManagerClient/builder)]
    (when region (.region builder (Region/of region)))
    (.build builder)))

(defn- fetch-secret [^SecretsManagerClient client secret-id]
  (try
    (let [request (-> (GetSecretValueRequest/builder) (.secretId secret-id) .build)]
      (.secretString (.getSecretValue client ^GetSecretValueRequest request)))
    (catch ResourceNotFoundException _ nil)))

(deftype SecretsManagerSource [^SecretsManagerClient client owns-client?]
  secrets/Source
  (-get-value [_ env scope k]
    (fetch-secret client (secret-name env scope k)))
  (-load-scope [_ env scope ks]
    (into {}
          (keep (fn [k] (when-let [v (fetch-secret client (secret-name env scope k))] [k v])))
          ks))
  (-close [_] (when owns-client? (.close client)))
  java.io.Closeable
  (close [this] (secrets/-close this)))

(defmethod secrets/create :secrets-manager [{:keys [client] :as spec}]
  (if client
    (SecretsManagerSource. client false)
    (SecretsManagerSource. (->client spec) true)))
