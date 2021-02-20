;; Copyright © 2021, JUXT LTD.

(ns juxt.pass.alpha.pdp
  (:require
   [clojure.set :as set]
   [clojure.walk :refer [postwalk-replace]]
   [clojure.tools.logging :as log]
   [jsonista.core :as json]
   [crux.api :as crux]
   [integrant.core :as ig]
   [juxt.pass.alpha :as pass]
   [juxt.site.alpha.response :as response]
   [juxt.site.alpha.entity :as entity]
   [juxt.site.alpha.util :refer [hexdigest]]
   [juxt.spin.alpha :as spin]))

;; PDP (Policy Decision Point)

;; See 3.2 of
;; http://docs.oasis-open.org/xacml/3.0/errata01/os/xacml-3.0-core-spec-errata01-os-complete.html#_Toc489959503

(defn authorization
  "Returns authorization information. Context is all the attributes that pertain
  to the subject, resource, request and environment and maybe more."
  [db request-context]

  ;; Map across each rule in the system (we can memoize later for
  ;; performance).

  (let [
        ;; TODO: But we should go through all policies, bring in their
        ;; attributes to merge into the target, then for each rule in the
        ;; policy... the apply rule-combining algo...

        rules (map first
                   (crux/q db '{:find [rule]
                                :where [[rule :type "Rule"]]}))

        _  (log/debugf "Rules to match are %s" (pr-str rules))

        temp-id-map (reduce-kv
                     (fn [acc k v] (assoc acc k (assoc v :crux.db/id (java.util.UUID/randomUUID))))
                     {} request-context)

        ;; Speculatively put each entry of the request context into the
        ;; database. This new database is only in scope for this authorization.
        db (crux/with-tx db (mapv (fn [e] [:crux.tx/put e]) (vals temp-id-map)))

        evaluated-rules
        (keep
         (fn [rule-id]
           (let [rule (crux/entity db rule-id)]
             (when-let [target (::pass/target rule)]
               (let [q {:find ['success]
                        :where (into '[[(identity true) success]] target)
                        :in (vec (keys temp-id-map))}
                     match-results (apply crux/q db q (map :crux.db/id (vals temp-id-map)))]
                 (assoc rule ::pass/matched? (pos? (count match-results)))))))
         rules)

        _ (log/debugf "Result of rule matching: %s" (pr-str evaluated-rules))

        matched-rules (filter ::pass/matched? evaluated-rules)

        _ (log/debug "Rejected rules" (pr-str (filter (comp not ::pass/matched?) evaluated-rules)))
        _ (log/debug "Matched rules" (pr-str matched-rules))

        allowed? (and
                  (pos? (count matched-rules))
                  ;; Rule combination algorithm is every?
                  (every? #(= (::pass/effect %) ::pass/allow) matched-rules))]

    (log/debugf "Allowed?: %s" allowed?)
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
      (cond-> query
        ;;(assoc :in '[context])
        combined-limiting-clauses (update :where (comp vec concat) combined-limiting-clauses)))))

(defmethod ig/init-key ::resources [_ {:keys [crux-node]}]
  (println "Adding built-in users/rules")
  (try
    (crux/submit-tx
     crux-node

     (concat
      ;; The webmaster user - in the future, the password will be provided when
      ;; the Crux instance is provisioned.
      (entity/user-entity "webmaster" "FunkyForest")

      ;; A rule that allows the webmaster to do everything, at least during the
      ;; bootstrap phase of a deployment. This can be deleted after the initial
      ;; users/roles have been populated, if required.
      [[:crux.tx/put
        {:crux.db/id "/_site/pass/rules/webmaster"
         :type "Rule"
         ::pass/target '[[subject :juxt.pass.alpha/username "webmaster"]]
         ::pass/effect ::pass/allow
         ::pass/allow-methods #{:get :head :options}}]]

      [[:crux.tx/put
        {:crux.db/id "/_site/pass/rules/accessible-public-resources"
         :type "Rule"
         :description "PUBLIC resources are accessible to GET"
         ::pass/target '[[request :request-method #{:get :head :options}]
                         [resource ::pass/classification "PUBLIC"]]
         ::pass/effect ::pass/allow}]]))

    (catch Exception e
      (prn e))))
