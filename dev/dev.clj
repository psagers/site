(ns dev
  (:require
   [dev-extras :refer :all]
   [crux.api :as crux]
   [juxt.spin.alpha :as spin]))

(defn crux-node []
  (:juxt.site.alpha.db/crux-node system))

(defn db []
  (crux/db (crux-node)))

(defn e [id]
  (crux/entity (db) id))

(defn put [m]
  (->>
   (crux/submit-tx
    (crux-node)
    [[:crux.tx/put
      m]])
   (crux/await-tx (crux-node))))

(defn d [id]
  (->>
   (crux/submit-tx
    (crux-node)
    [[:crux.tx/delete id]])
   (crux/await-tx (crux-node))))

(defn q [query]
  (crux/q (db) query))

(defn es []
  (sort-by
   str
   (map first
        (q '{:find [e] :where [[e :crux.db/id]]}))))

(defn rules []
  (sort-by
   str
   (map first
        (q '{:find [(eql/project e [*])] :where [[e :type "Rule"]]}))))

(defn uuid [s]
  (cond
    (string? s) (java.util.UUID/fromString s)
    (uuid? s) s))

(defn uri [s]
  (cond
    (string? s) (java.net.URI. s)
    (uri? s) s))

(defn we
  "Lookup a 'web entity'"
  [u]
  (e (uri u)))

(defn wes
  "List all web entities"
  []
  (sort
   (for [[e m] (q '{:find [e (distinct m)] :where [[e ::spin/methods m]]})
         :let [ent (crux/entity (db) e)]]
     [(str e) m (count (::spin/representations ent))])))


(defn init-db [admin-password]


  )
