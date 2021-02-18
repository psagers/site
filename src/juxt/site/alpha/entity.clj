;; Copyright © 2021, JUXT LTD.

(ns juxt.site.alpha.entity
  (:require
   [clojure.pprint :refer [pprint]]
   [crypto.password.bcrypt :as password]
   [jsonista.core :as json]
   [juxt.pass.alpha :as pass]
   [juxt.site.alpha :as site]
   [juxt.site.alpha.util :refer [hexdigest sanitize]]
   [juxt.spin.alpha :as spin])
  (:import
   (java.util Date UUID)))

(defn new-data-resource [uri resource-state]
  (let [last-modified (Date.)
        ;; As a hash of the state of the resource, the etag is shared across
        ;; representations. The Vary header will ensure that a cache will use
        ;; the content-type as a secondary key.
        etag
        ;; TODO: Use a proper reap encoder to ensure we don't create invalid
        ;; etags
        (format "\"%s\"" (subs (hexdigest (.getBytes (pr-str resource-state) "utf-8")) 0 16))]
    (concat
     [;; Resource
      [:crux.tx/put
       (into
        resource-state
        {:crux.db/id uri
         ::spin/methods #{:get :head :options}
         ::spin/representations
         [(let [content (str (with-out-str (pprint (sanitize resource-state)) "\r\n"))
                charset "utf-8"
                bytes (.getBytes content charset)]
            {::spin/content-type "application/edn"
             ::spin/charset charset
             ::spin/etag etag
             ::spin/last-modified last-modified
             ::spin/content-length (count bytes)
             ::spin/content content})
          (let [content (str (json/write-value-as-string (sanitize resource-state)) "\r\n")
                charset "utf-8"
                bytes (.getBytes content "utf-8")]
            {::spin/content-type "application/json"
             ::spin/charset charset
             ::spin/etag etag
             ::spin/last-modified last-modified
             ::spin/content-length (count bytes)
             ::spin/content content})]})]])))

(defn user-entity [username password]
  (new-data-resource
   (format "/_site/pass/users/%s" username)
   {::pass/username username
    ::pass/password-hash!! (password/encrypt password)}))
