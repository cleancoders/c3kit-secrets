# Secrets

A library component of [c3kit - Clean Coders Clojure Kit](https://github.com/cleancoders/c3kit).

[![Secrets Build](https://github.com/cleancoders/c3kit-secrets/actions/workflows/ci.yml/badge.svg)](https://github.com/cleancoders/c3kit-secrets/actions/workflows/ci.yml)
[![Clojars Project](https://img.shields.io/clojars/v/com.cleancoders.c3kit/secrets.svg)](https://clojars.org/com.cleancoders.c3kit/secrets)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Secrets loads an application's secrets and runtime configuration from
[AWS Systems Manager Parameter Store](https://docs.aws.amazon.com/systems-manager/latest/userguide/systems-manager-parameter-store.html)
at startup.

* __c3kit.secrets__ : the API — `get-value`, `load-all`, and their throwing `!` variants
* __c3kit.secrets.ssm__ : the SSM Parameter Store source
* __c3kit.secrets.secrets-manager__ : a *transitional* AWS Secrets Manager source, for migrating off Secrets Manager

## Installation

Add to your `deps.edn`:

```clojure
com.cleancoders.c3kit/secrets {:mvn/version "0.1.0"}
```

**Requirements:** Clojure 1.11+, JDK 21+. JVM only.

## Quickstart

Values are addressed by **environment**, **scope** and **key**. In Parameter
Store they live at `/{env}/{scope}/{key}`:

```
/staging/shared/jwt-secret     SecureString
/staging/myapp/api-token       SecureString
/staging/myapp/redis-host      String
```

Store anything sensitive as a `SecureString` and plain configuration as a
`String`. Both are read with decryption, so the app does not need to know which
is which.

```clojure
(require '[c3kit.secrets :as secrets]
         '[c3kit.secrets.ssm])

(with-open [source (secrets/create {:impl :ssm :region "us-west-2"})]
  (secrets/load-all! source "staging" {"shared" [:jwt-secret]
                                       "myapp"  [:api-token :redis-host]}))
;; => {:jwt-secret "..." :api-token "..." :redis-host "..."}
```

`load-all!` makes one `GetParametersByPath` call per scope (paginated) and
returns a flat map. It throws if any requested key is missing, naming every
missing `scope/key` — never a value. `load-all` returns what it found and omits
missing keys. `get-value` / `get-value!` read a single value.

A key requested from two scopes would collide in the flat map, so it throws.

### In an app

A typical startup hook reads everything once and builds config from it:

```clojure
(def secret-keys
  {"shared" [:jwt-secret :google-oauth-client-secret]
   "myapp"  [:api-token :redis-host]})

(defn load-secrets! [environment]
  (with-open [source (secrets/create {:impl :ssm :region "us-west-2"})]
    (let [s (secrets/load-all! source environment secret-keys)]
      (alter-var-root #'env merge
                      {:jwt           {:secret (:jwt-secret s)}
                       :message-queue {:impl :redis :host (:redis-host s) :tls? true}}))))
```

For development and specs, use the in-memory source:

```clojure
(secrets/create {:impl :memory :values {"development" {"myapp" {:redis-host "localhost"}}}})
```

## Sources

| `:impl` | Naming | Use |
|---|---|---|
| `:ssm` | `/{env}/{scope}/{key}` | **The primary source.** Require `c3kit.secrets.ssm`. |
| `:memory` | n/a | Development and specs. |
| `:chain` | each source's own | Tries `:sources` in order; the first value found wins. For migrations. |
| `:secrets-manager` | `{scope}/{env}/{key}` | **Transitional — deprecated on arrival.** Only for reading values not yet moved to SSM. Require `c3kit.secrets.secrets-manager`. |

Both AWS sources accept `:region` (otherwise the AWS SDK's default region chain
applies) and `:client` (an SDK client to use instead of building one). A client
you pass in is yours to close; one the source builds is closed by
`secrets/close` or `with-open`.

Errors other than "not found" — access denied, throttling, network — are not
swallowed. A source that cannot be read fails loudly rather than silently
falling through to the next source in a chain.

### IAM

The `:ssm` source needs, for each env/scope it reads:

```json
{"Effect": "Allow",
 "Action": ["ssm:GetParametersByPath", "ssm:GetParameter"],
 "Resource": ["arn:aws:ssm:REGION:ACCOUNT:parameter/ENV/SCOPE",
              "arn:aws:ssm:REGION:ACCOUNT:parameter/ENV/SCOPE/*"]}
```

`SecureString` parameters encrypted with the default `aws/ssm` key need no extra
permission. Parameters under a customer-managed KMS key also need `kms:Decrypt`
on that key.

## Migrating from Secrets Manager

The `:secrets-manager` source and `:chain` exist so an app can move to
Parameter Store without a flag day. The goal is **no Secrets Manager secrets at
all**; once a migration is done, remove both.

1. Grant the app's role the SSM permissions above. Do this first: with SSM at
   the front of a chain, a missing permission fails startup.
2. Ship the app reading SSM first, Secrets Manager second:

   ```clojure
   (secrets/create {:impl    :chain
                    :sources [{:impl :ssm :region "us-west-2"}
                              {:impl :secrets-manager :region "us-west-2"}]})
   ```

   Secrets Manager names are `{scope}/{env}/{key}`; SSM names are
   `/{env}/{scope}/{key}`. The scope and key stay the same.
3. Copy each value to SSM — `SecureString` for secrets, `String` for config.
   The chain only asks Secrets Manager for keys SSM did not have, so values can
   move one at a time.
4. When nothing is read from Secrets Manager any more (its "last accessed" date
   stops moving), switch the app to `{:impl :ssm}` alone and delete the Secrets
   Manager secrets.

## Development

```sh
clj -M:test:spec      # JVM specs (one-shot)
clj -M:test:spec -a   # JVM specs (auto-runner)
```

The specs never call AWS: the AWS sources are tested against fake SDK clients.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow, TDD
expectations, and pull-request requirements. This project follows the
[Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md). Security issues
should be reported privately — see [SECURITY.md](SECURITY.md).

## Deployment

Releases run in CI. `clj -T:build deploy` refuses to run outside GitHub Actions,
so the sanctioned path always carries the CI check and leaves an audit trail.

1. Open a PR bumping `resources/c3kit/secrets/VERSION` and `CHANGES.md`.
2. Merge to `master` and wait for **Secrets Build** to go green. The version bump
   is part of the merged commit, so the commit CI validated is the commit that
   gets released.
3. Actions → **Release** → **Run workflow**.
4. Approve the `clojars` deployment when prompted.

The workflow verifies **Secrets Build** succeeded for that exact commit, builds
the jar, publishes to Clojars, and only then pushes the version tag. A failed
publish therefore leaves no tag.

`clj -T:build jar` builds without publishing. `clj -T:build install` installs to
`~/.m2` for local testing.

## License

[MIT](LICENSE) © Clean Coders.
