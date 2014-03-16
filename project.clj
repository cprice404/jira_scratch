(defproject cprice404/jira-scratch "0.0.1-SNAPSHOT"
  ;; Abort when version ranges or version conflicts are detected in
  ;; dependencies. Also supports :warn to simply emit warnings.
  ;; requires lein 2.2.0+.
  :pedantic? :abort

  :dependencies [[org.clojure/clojure "1.5.1"]
                 ;; Logging
                 [org.clojure/tools.logging "0.2.6"]
                 [cheshire "5.2.0"]
                 [clj-http "0.5.3"]
                 [me.raynes/fs "1.4.4"]
                 [com.cemerick/url "0.1.1"]]
)
