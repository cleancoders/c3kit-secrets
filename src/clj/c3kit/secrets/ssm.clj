(ns c3kit.secrets.ssm
  "AWS SSM Parameter Store source — the primary c3kit.secrets source.

   Values live at /{env}/{scope}/{key}, e.g. /staging/epic/redis-host. Store
   secrets as SecureString and plain configuration as String; both are read
   with decryption, so callers do not need to know which is which.

   spec:
     {:impl   :ssm
      :region \"us-west-2\"  ; optional; otherwise the SDK's default region chain
      :client ssm-client}    ; optional SsmClient. A client passed in is the
                             ; caller's to close; one built here is closed by
                             ; c3kit.secrets/close.

   IAM: loading a scope uses ssm:GetParametersByPath on
   arn:aws:ssm:{region}:{account}:parameter/{env}/{scope}; a single value uses
   ssm:GetParameter on .../parameter/{env}/{scope}/{key}. SecureStrings under a
   customer-managed KMS key also need kms:Decrypt on that key (the default
   aws/ssm key needs nothing extra)."
  (:require [c3kit.secrets :as secrets]
            [clojure.string :as str])
  (:import (software.amazon.awssdk.regions Region)
           (software.amazon.awssdk.services.ssm SsmClient SsmClientBuilder)
           (software.amazon.awssdk.services.ssm.model GetParameterRequest
                                                      GetParametersByPathRequest
                                                      GetParametersByPathResponse
                                                      Parameter
                                                      ParameterNotFoundException)))

(defn parameter-name
  "The SSM parameter name for env/scope/key: /{env}/{scope}/{key}."
  [env scope k]
  (str "/" env "/" scope "/" (name k)))

(defn- scope-path [env scope] (str "/" env "/" scope "/"))

(defn ->client
  "Builds an SsmClient, using :region when given."
  ^SsmClient [{:keys [region]}]
  (let [^SsmClientBuilder builder (SsmClient/builder)]
    (when region (.region builder (Region/of region)))
    (.build builder)))

(defn- fetch-parameter [^SsmClient client parameter]
  (try
    (let [request (-> (GetParameterRequest/builder)
                      (.name parameter)
                      (.withDecryption true)
                      .build)]
      (-> (.getParameter client ^GetParameterRequest request) .parameter .value))
    (catch ParameterNotFoundException _ nil)))

(defn- fetch-page ^GetParametersByPathResponse [^SsmClient client path token]
  (let [builder (-> (GetParametersByPathRequest/builder)
                    (.path path)
                    (.recursive false)
                    (.withDecryption true))]
    (when token (.nextToken builder token))
    (.getParametersByPath client ^GetParametersByPathRequest (.build builder))))

(defn- fetch-path [client path]
  (loop [token nil parameters []]
    (let [page       (fetch-page client path token)
          parameters (into parameters (.parameters page))
          token      (.nextToken page)]
      (if (str/blank? token) parameters (recur token parameters)))))

(deftype SsmSource [^SsmClient client owns-client?]
  secrets/Source
  (-get-value [_ env scope k]
    (fetch-parameter client (parameter-name env scope k)))
  (-load-scope [_ env scope ks]
    (let [path   (scope-path env scope)
          wanted (set ks)]
      (into {}
            (keep (fn [^Parameter parameter]
                    (let [k (keyword (subs (.name parameter) (count path)))]
                      (when (wanted k) [k (.value parameter)]))))
            (fetch-path client path))))
  (-close [_] (when owns-client? (.close client)))
  java.io.Closeable
  (close [this] (secrets/-close this)))

(defmethod secrets/create :ssm [{:keys [client] :as spec}]
  (if client
    (SsmSource. client false)
    (SsmSource. (->client spec) true)))
