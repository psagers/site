;; Copyright © 2021, JUXT LTD.

(ns dev
  (:require
   [clojure.java.io :as io]
   [crux.api :as crux]
   [dev-extras :refer :all]
   [juxt.spin.alpha :as spin])
  (:import (java.io DataInputStream FileInputStream)))

(defn crux-node []
  (:juxt.site.alpha.db/crux-node system))

(defn db []
  (crux/db (crux-node)))

(defn e [id]
  (crux/entity (db) id))

(defn put [& ms]
  (->>
   (crux/submit-tx
    (crux-node)
    (for [m ms]
      [:crux.tx/put m]))
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

(defn slurp-file-as-bytes [f]
  (let [f (io/file f)
        size (.length f)
        bytes (byte-array size)]
    (.readFully (DataInputStream. (FileInputStream. f)) bytes)
    bytes))

(defn init-db [#_admin-password]
  (println "Initializing Site Database")

  (put
   {:crux.db/id "/css/styles.css"
    ::spin/methods #{:get :head :option}
    ::spin/representations
    [(let [bytes (slurp-file-as-bytes "style/target/styles.css")]
       {::spin/content-type "text/css"
        ::spin/content-length (count bytes)
        ::spin/bytes bytes})
     (let [bytes (slurp-file-as-bytes "style/target/styles.css.gz")]
       {::spin/content-type "text/css"
        ::spin/content-encoding "gzip"
        ::spin/content-length (count bytes)
        ::spin/bytes bytes})
     (let [bytes (slurp-file-as-bytes "style/target/styles.css.br")]
       {::spin/content-type "text/css"
        ::spin/content-encoding "br"
        ::spin/content-length (count bytes)
        ::spin/bytes bytes})]}))
