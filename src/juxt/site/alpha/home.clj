;; Copyright © 2021, JUXT LTD.

(ns juxt.site.alpha.home
  (:require
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [crux.api :as crux]
   [hiccup.page :as hp]
   [integrant.core :as ig]
   [juxt.spin.alpha :as spin]
   [juxt.pass.alpha :as pass]
   [juxt.site.alpha.payload :as payload]))

(defmethod ig/init-key ::resources [_ {:keys [crux-node]}]
  (println "Adding home page resources")
  (try
    (crux/submit-tx
     crux-node
     [[:crux.tx/put
       {:crux.db/id "/"
        ::spin/redirect "/index.html"}]

      [:crux.tx/put
       {:crux.db/id "/index.html"
        ::spin/methods #{:get :head :options}
        ::pass/classification "PUBLIC"
        ::spin/representations
        [{::spin/content-type "text/html;charset=utf-8"
          ::spin/content
          (hp/html5
           [:h2 "Welcome to site"]
           [:form {:method "POST" :action "/_site/login"}
            [:div
             [:label "Username"]
             [:input {:style "margin: 4pt" :name "user" :type "text"}]]
            [:div
             [:label "Password"]
             [:input {:style "margin: 4pt" :name "password" :type "password"}]]
            [:div
             [:input {:style "margin: 4pt"
                      :type "submit"
                      :value "Login"}]]])}]}]

      (let [bytes (.readAllBytes (io/input-stream (io/resource "juxt/site/alpha/favicon.ico")))]
        [:crux.tx/put
         {:crux.db/id "/favicon.ico"
          ::pass/classification "PUBLIC"
          ::spin/methods #{:get :head :options}
          ::spin/representations
          [{::spin/content-type "image/x-icon"
            ::spin/content-length (count bytes)
            ::spin/bytes bytes}]}])])

    (catch Exception e
      (log/error e "Failed to add home page resources"))))
