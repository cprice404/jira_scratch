(ns puppetlabs.jira-scratch
  (:require [clj-http.client :as client]
            [clojure.string :as str]
            [me.raynes.fs :as fs]
            [cheshire.core :as json]
            [cemerick.url :as url]))

(defn get-jira-auth
  []
  (str/trim (slurp (fs/expand-home "~/.jira_auth"))))

(defn changes-to-ready-for-merge?
  [entry]
  (some #(and (= "status" (% "field"))
              (= "Ready for Merge" (% "toString")))
        (entry "items")))

(defn changes-to-in-progress?
  [entry]
  (some #(and (= "status" (% "field"))
              (= "In Progress" (% "toString")))
        (entry "items")))

(defn find-self-assignments
  [owner entry]
  (let [author (get-in entry ["author" "name"])]
    (if (some #(and (= "assignee" (% "field"))
                    (= author (% "to"))
                    (not= author owner))
              (entry "items"))
      [author])))

(defn find-assignee-change
  [entry]
  (let [assignee-changes (filter #(= "assignee" (% "field")) (entry "items"))]
    (if-not (empty? assignee-changes)
      ((last assignee-changes) "to"))))

(defn find-ready-for-merge*
  [owner entries]
  (if-not (empty? entries)
    (let [entry (first entries)
          owner (or (find-assignee-change entry) owner)]
      (if (changes-to-ready-for-merge? entry)
        [owner (rest entries)]
        (recur owner (rest entries))))))

(defn find-ready-for-merge
  [entries]
  (find-ready-for-merge* nil entries))

(defn find-self-assignment-after-ready-for-merge
  [issue]
  (let [entries (get-in issue ["changelog" "histories"])
        [owner remaining-entries] (find-ready-for-merge entries)]
    (if-not remaining-entries
      (throw (IllegalStateException.
               (str "Did not find 'ready for merge' state change for issue"
                    (issue "key"))))
      (let [self-assignments (set (mapcat (partial find-self-assignments owner) remaining-entries))]
        {:id                (issue "key")
         :owner             owner
         :self-assignments  (if-not (empty? self-assignments)
                              self-assignments)
         :summary           (get-in issue ["fields" "summary"])}))))

#_(defn history-for-issue
  [issue]
  (doseq [history-entry (get-in issue ["changelog" "histories"])]
    (println
      (format "%s made a change on %s:"
              (get-in history-entry ["author" "name"])
              (get-in history-entry ["created"])))
    (doseq [field-entry (get-in history-entry ["items"])]
      (println
        (format "\t%s: %s -> %s"
                (field-entry "field")
                (field-entry "fromString")
                (field-entry "toString"))))))

(defn get-green-team-query
  [sprint-start]
  (str
    (format "status changed to 'Ready for Merge' after '%s'" sprint-start)
    "and ('Epic Link' = PE-1818 or 'Epic Link' = PE-2503)"))

(defn get-reviewable-issues
  [sprint-start]
  (let [result (-> "https://tickets.puppetlabs.com/rest/api/latest/search?jql=%s&expand=changelog"
                   (format
                     (url/url-encode
                       (get-green-team-query sprint-start)))
                   (client/get {:basic-auth (get-jira-auth)})
                   :body
                   json/decode)]
    (result "issues")))

(defn inc-num-issues
  [acc]
  (update-in acc [:num-issues] inc))

(defn inc-num-reviewed-issues
  [acc reviewed?]
  (if reviewed?
    (update-in acc [:num-reviewed-issues] inc)
    acc))

(defn inc-owned
  [acc owner]
  (if-not (get-in acc [:users owner :owned])
    (assoc-in acc [:users owner :owned] 1)
    (update-in acc [:users owner :owned] inc)))

(defn inc-reviewer
  [acc reviewer]
  (if-not (get-in acc [:users reviewer :reviewed])
    (assoc-in acc [:users reviewer :reviewed] 1)
    (update-in acc [:users reviewer :reviewed] inc)))

(defn inc-reviewers
  [acc reviewers]
  (reduce inc-reviewer acc reviewers))

(defn count-owned-and-reviewed
  [acc issue]
  (let [issue     (find-self-assignment-after-ready-for-merge issue)
        reviewed? (> (count (issue :self-assignments)) 0)]
    (if (issue :self-assignments)
      (println (format "Issue %s (%s) was reviewed by %s. (%s)"
                       (issue :id)
                       (issue :owner)
                       (issue :self-assignments)
                       (issue :summary)))
      (println (format "Issue %s (%s) was not reviewed. (%s)"
                       (issue :id)
                       (issue :owner)
                       (issue :summary))))
    (-> acc
        (inc-num-issues)
        (inc-num-reviewed-issues reviewed?)
        (inc-reviewers (issue :self-assignments))
        (inc-owned (issue :owner)))))

(defn find-reviewed-issues
  [sprint-start]
  (let [reviewable-issues (get-reviewable-issues sprint-start)]
    (reduce count-owned-and-reviewed
            {:num-issues 0
             :num-reviewed-issues 0}
            reviewable-issues)))