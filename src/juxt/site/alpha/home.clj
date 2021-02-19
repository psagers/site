;; Copyright © 2021, JUXT LTD.

(ns juxt.site.alpha.home
  (:require
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [crux.api :as crux]
   [hiccup.page :as hp]
   [clojure.pprint :refer [pprint]]
   [integrant.core :as ig]
   [juxt.spin.alpha :as spin]
   [juxt.pass.alpha :as pass]
   [juxt.site.alpha.payload :as payload]))

;; This ns is definitely optional

(defmethod payload/generate-representation-body ::user-home-page
  [request resource representation db authorization subject]
  ;;(log/trace (::pass/username subject) (::user resource))
  (case (::spin/content-type representation)
    "text/html;charset=utf-8"
    (if (= (::pass/username subject) (::owner resource))
      (let [user (crux/entity db (::pass/user subject))]
        (hp/html5
         [:h1 (format "Hello %s!" (:name user))]

         [:p "Welcome to Site"]

         [:p "Click on the button below to create your home area"]

         [:p "But first, read this legal stuff and provide your consent, etc."]

         [:small " Lorem ipsum dolor sit amet, consectetur adipiscing elit. Morbi cursus sem libero, in viverra magna tincidunt a. Lorem ipsum dolor sit amet, consectetur adipiscing elit. Ut lacus quam, sagittis id nisl tristique, volutpat consequat lectus. Nunc arcu dui, ullamcorper consectetur ornare nec, lacinia vitae nibh. Suspendisse fermentum malesuada ante, sed placerat lorem lobortis sed. Nullam bibendum interdum arcu, eu commodo ligula pulvinar nec. Lorem ipsum dolor sit amet, consectetur adipiscing elit. Etiam sit amet semper ligula, imperdiet egestas ipsum. Duis aliquet ex id nisi ultrices, id aliquam leo tincidunt. Aenean interdum id leo eget tempor. Cras massa felis, sodales ac iaculis nec, aliquam ut mauris. Pellentesque aliquet mattis ex, at semper est condimentum nec.

Pellentesque malesuada leo at ex venenatis facilisis. Quisque nec dictum metus, in tincidunt lectus. Quisque massa nisl, lobortis vel tellus quis, porttitor ultricies odio. Phasellus tristique urna sit amet nulla tristique, et tincidunt nunc egestas. Etiam sodales semper maximus. Nam aliquam tortor mauris. Mauris convallis erat vitae tellus mollis fringilla. Ut sit amet lobortis magna, non euismod ante. Lorem ipsum dolor sit amet, consectetur adipiscing elit. Nullam efficitur vitae nisi vel pretium.

Curabitur id sollicitudin ex. Fusce mollis risus cursus nisl lacinia ullamcorper. Phasellus porttitor ante at nisl tempor rhoncus. Duis sit amet velit sodales, ultricies odio ut, facilisis felis. Morbi elementum elit urna, a ultrices est vulputate vitae. Suspendisse non augue et metus dictum placerat. Praesent id diam efficitur metus malesuada scelerisque. Integer porttitor velit id massa sodales hendrerit. Phasellus porta nunc et lacus egestas congue. Nulla suscipit facilisis semper. Pellentesque vulputate bibendum mauris vitae mollis. In eleifend pharetra elit eget vehicula. Pellentesque ac vehicula ex, sed lacinia dui. "]

         [:p "Now let's create your page!"]

         [:form {:method "POST"}
          [:input {:type "submit" :value "Create my home page"}]]))

      (throw
       (ex-info
        "User's page isn't yet created"
        {::spin/response {:status 404 :body "Not Found\r\n\r\n(but coming soon!)\r\n"}})))))

(defn locate-resource [request]
  ;; Add a trailing slash if necessary
  (when-let [[_ _] (re-matches #"/~(\p{Alpha}[\p{Alnum}_-]*)$" (:uri request))]
    (throw ;; TODO: Promote this to a spin function
     (ex-info
      "Add trailing space"
      {::spin/response {:status 302 :headers {"location" (str (:uri request) "/")}}})))

  (when-let [[_ owner] (re-matches #"/~(\p{Alpha}[\p{Alnum}_-]*)/" (:uri request))]
    {::spin/methods #{:get :head :options}
     ::owner owner
     ::spin/representations
     [{::spin/content-type "text/html;charset=utf-8"
       ::spin/bytes-generator ::user-home-page}]}))

(defmethod payload/generate-representation-body ::home-page
  [request resource representation db authorization subject]
  ;; A default page (if one doesn't exist)
  (.getBytes
   (hp/html5
    [:h2 "Welcome to site"]
    (if-let [username (::pass/username subject)]
      (let [user (crux/entity db (::pass/user subject))]
        [:div
         [:p "You are logged in as " username]

         [:p "TODO: Show auth method, if cookie, then allow to logout"]

         [:p [:a {:href (format "/~%s/" username)} "My page"]]])

      ;; Otherwise let them login
      [:div
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
                  :value "Login"}]]]])
    )
   "UTF-8"))

;; Perhaps belongs elsewhere
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
          ::spin/bytes-generator ::home-page
          }]}]

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
