(defproject com.hyperphor/ellum "0.1.3"
  :description "Unified multi-provider LLM client library"
  :url "https://github.com/hyperphor/ellum"
  :license {:name "MIT"}
  :deploy-repositories [["clojars" {:sign-releases false
                                     :username :env/clojars_username
                                     :password :env/clojars_password}]]
  :dependencies [[org.clojure/clojure "1.11.3"]
                 [hato "1.0.0"]
                 [cheshire "6.1.0"]      ; hato's `:as :json` silently no-ops without this
                 [org.clojure/data.json "2.5.2"]
                 [environ "1.2.0"]
                 [com.hyperphor/multitool "0.3.0"]
                 [org.clojure/core.async "1.6.681"]]
  :source-paths ["src/clj"]
  :test-paths ["test/clj"]
  :profiles {:dev {:dependencies [[clj-http-fake "1.0.4"]]}})
