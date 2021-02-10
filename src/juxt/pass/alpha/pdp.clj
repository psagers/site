;; Copyright © 2021, JUXT LTD.

(ns juxt.pass.alpha.pdp
  (:require
   [clojure.set :as set]
   [clojure.walk :refer [postwalk-replace]]
   [clojure.tools.logging :as log]
   [crux.api :as crux]
   [juxt.pass.alpha :as pass]
   [juxt.spin.alpha :as spin]
   [juxt.spin.alpha.auth :as spin.auth]))

;; PDP (Policy Decision Point)

(defn- request-decision [db context]
  (let [
        ;; TODO: But we should go through all policies, bring in their
        ;; attributes to merge into the target, then for each rule in the
        ;; policy... the apply rule-combining algo...

        rules (crux/q db '{:find [rule (distinct target-clauses)]
                           :where [[rule ::pass/target target-clauses]]})

        _  (log/debugf "Rules to match are %s" (pr-str rules))
        context-id (java.util.UUID/randomUUID)
        _ (log/debugf "Submitting context to db: %s" context)

        db (crux/with-tx db
             [[:crux.tx/put (assoc context :crux.db/id context-id)]
              ])]

    ;; Map across each rule in the system (we can memoize later for
    ;; performance).
    (map
     (fn [[rule target-clauses]]
       (let [rule-ent (crux/entity db rule)]
         (assoc
          rule-ent
          ::pass/matched?
          (contains?
           (set (map first (crux/q db {:find ['context]
                                       :where (into [['context :crux.db/id 'context-id]]
                                                    target-clauses)
                                       :in ['context-id]}
                                   context-id)))
           context-id))))
     rules)))

(defn authorization [db context]
  (let [
        ;; See 3.2 of
        ;; http://docs.oasis-open.org/xacml/3.0/errata01/os/xacml-3.0-core-spec-errata01-os-complete.html#_Toc489959503

        ;; This is all the attributes that pertain to the subject, resource,
        ;; action, environment and maybe more.

        decision (request-decision db context)

        _ (log/debugf "Decision: %s" (pr-str decision))

        matched-rules (filter ::pass/matched? decision)

        allowed? (and
                  (pos? (count matched-rules))
                  ;; Rule combination algorithm is every?
                  (every? #(= (::pass/effect %) ::pass/allow) matched-rules))]

    (if-not allowed?
      #::pass
      {:access ::pass/denied
       :matched-rules matched-rules}

      #::pass
      {:access ::pass/approved
       :matched-rules matched-rules
       :limiting-clauses
       ;; Get all the query limits added by these rules
       (->> matched-rules
            (filter #(= (::pass/effect %) ::pass/allow))
            (map ::pass/limiting-clauses)
            (apply concat))
       :allow-methods
       (->> matched-rules
            (filter #(= (::pass/effect %) ::pass/allow))
            (map ::pass/allow-methods)
            (apply set/union))})))

(defn ->authorized-query [query authorization]
  ;; Ensure they apply to ALL entities queried by a given Datalog
  ;; query. We duplicate each set of limiting clauses. For each copy,
  ;; we replace 'e (which, by convention, is what we use in a limiting
  ;; clause), with the actual symbols used in the given Datalog query.
  (when (= (::pass/access authorization) ::pass/approved)
    (let [combined-limiting-clauses
          (apply concat (for [sym (distinct (filter symbol? (map first (:where query))))]
                          (postwalk-replace {'e sym} (::pass/limiting-clauses authorization))))]
      (-> query
          (assoc :in '[context])
          (update :where (comp vec concat) combined-limiting-clauses)))))


(defn authorize
  "Return the resource, as it appears to the request after authorization rules
  have been applied."
  [request resource db]

  (let [username (get-in request [::pass/user ::pass/username])]
    (when resource
      (cond

        ;; Give the crux admin god-like power
        (= username "crux/admin")
        resource

        (and
         (.startsWith (:uri request) "/_crux/")
         (not= username "crux/admin"))
        (throw
         (if username
           (ex-info
            "Forbidden"
            {::spin/response
             {:status 403
              :body "Forbidden\r\n"}})

           (ex-info
            "Unauthorized"
            {::spin/response
             {:status 401
              :headers
              {"www-authenticate"
               (spin.auth/www-authenticate
                [{::spin/authentication-scheme "Basic"
                  ::spin/realm "Crux Administration"}])}
              :body "Unauthorized\r\n"}})))

        :else
        (let [request-context {:request (dissoc request :body)
                               :resource resource}
              authorization (authorization db request-context)]

          (prn authorization)

          (if-not (= (::pass/access authorization) ::pass/approved)
            (throw
             (if username
               (ex-info
                "Forbidden"
                {::spin/response
                 {:status 403
                  :body "Forbidden\r\n"}})

               (ex-info
                "Unauthorized"
                {::spin/response
                 {:status 401
                  :headers
                  {"www-authenticate"
                   (spin.auth/www-authenticate
                    [{::spin/authentication-scheme "Basic"
                      ::spin/realm "Users"}])}
                  :body "Unauthorized\r\n"}})))

            resource))))))
