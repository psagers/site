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
   [juxt.site.alpha :as site]
   [juxt.pass.alpha :as pass]
   [juxt.site.alpha.payload :as payload]))

;; This ns is definitely optional

(defmethod payload/generate-representation-body ::user-home-page
  [request resource representation db authorization subject]
  ;;(log/trace (::pass/username subject) (::user resource))
  (let [owner (crux/entity db (::owner resource))
        bookings (map first (crux/q db '{:find [(eql/project e [*])]
                                           :where [[e :type "WorkBooking"]]}))]

    (case (::spin/content-type representation)
      "text/html;charset=utf-8"

      (let [owner? (= (::pass/user subject) (::owner resource))
            prefix (if owner? "My" (str (:name owner) "'s"))]
        (hp/html5

         (if owner?
           [:h1 "My page"]
           [:h1 (str (:name owner) "'s" " page")])

         [:h2 (str prefix " timesheets")]
         [:table {:style "border: 1px solid black; border-collapse: collapse"}
          [:tbody
           (for [booking bookings]
             [:tr
              [:td (:title booking)]
              [:td (:state booking)]])]]

         [:h2 "My holidays"]
         [:p "Coming soon!"]

         [:h2 "My APIs"]
         [:p "Coming soon!"]

         [:h2 "My Collections"]
         [:p "Coming soon!"]

         [:h2 "My Kanbans"]
         [:p "Coming soon!"])))))

(defn create-user-home-page [request crux-node subject]
  (crux/submit-tx
   crux-node
   [[:crux.tx/put
     {:crux.db/id (:uri request)
      ::spin/methods #{:get :head :options}
      ::pass/classification "PUBLIC"
      ::owner (::pass/user subject)
      ::spin/representations
      [{::spin/content-type "text/html;charset=utf-8"
        ::spin/bytes-generator ::user-home-page}]}]]))

(defmethod payload/generate-representation-body ::user-empty-home-page
  [request resource representation db authorization subject]
  ;;(log/trace (::pass/username subject) (::user resource))
  (case (::spin/content-type representation)
    "text/html;charset=utf-8"
    (if (= (::pass/user subject) (::owner resource))
      ;; TODO: Perhaps better to look up the entity at authentication and put into subject
      (let [user (crux/entity db (::pass/user subject))]
        (hp/html5
         [:h1 (format "Hello %s!" (:name user))]

         [:p "Welcome to Site"]

         [:p "Click on the button below to create your home area"]

         [:p "But first, read this legal stuff and provide your consent, etc."]

         [:small " Lorem ipsum dolor sit amet, consectetur adipiscing
         elit. Morbi cursus sem libero, in viverra magna tincidunt a. Lorem
         ipsum dolor sit amet, consectetur adipiscing elit. Ut lacus quam,
         sagittis id nisl tristique, volutpat consequat lectus. Nunc arcu dui,
         ullamcorper consectetur ornare nec, lacinia vitae nibh. Suspendisse
         fermentum malesuada ante, sed placerat lorem lobortis sed. Nullam
         bibendum interdum arcu, eu commodo ligula pulvinar nec. Lorem ipsum
         dolor sit amet, consectetur adipiscing elit. Etiam sit amet semper
         ligula, imperdiet egestas ipsum. Duis aliquet ex id nisi ultrices, id
         aliquam leo tincidunt. Aenean interdum id leo eget tempor. Cras massa
         felis, sodales ac iaculis nec, aliquam ut mauris. Pellentesque aliquet
         mattis ex, at semper est condimentum nec."]

         [:p "Now let's create your page!"]

         [:form {:method "POST"}
          [:input {:type "submit" :value "Create my home page"}]]))

      (throw
       (ex-info
        "User's page isn't yet created"
        {::spin/response {:status 404 :body "Not Found\r\n\r\n(but coming soon!)\r\n"}})))))

(defn locate-resource [request db]
  ;; Add a trailing slash if necessary
  (when-let [[_ _] (re-matches #"/~(\p{Alpha}[\p{Alnum}_-]*)$" (:uri request))]
    (throw ;; TODO: Promote this to a spin function
     (ex-info
      "Add trailing space"
      {::spin/response {:status 302 :headers {"location" (str (:uri request) "/")}}})))

  (when-let [[_ owner] (re-matches #"/~(\p{Alpha}[\p{Alnum}_-]*)/" (:uri request))]
    (when-let [user (crux/entity db (format "/_site/pass/users/%s" owner))]
      {::site/resource-provider ::empty-personal-home-page
       ::spin/methods #{:get :head :options :post}
       ::pass/classification "PUBLIC"
       ::owner (:crux.db/id user)
       ::spin/representations
       [{::spin/content-type "text/html;charset=utf-8"
         ::spin/bytes-generator ::user-empty-home-page}]})))

(defmethod payload/generate-representation-body ::home-page
  [request resource representation db authorization subject]
  ;; A default page (if one doesn't exist)
  (.getBytes
   (hp/html5

    (if-let [username (::pass/username subject)]
      (let [user (crux/entity db (::pass/user subject))]
        (list
         [:h2 "Welcome to site"]
         [:div
          [:p "You are logged in as " username]

          [:p "TODO: Show auth method, if cookie, then allow to logout"]

          [:p [:a {:href (format "/~%s/" username)} "My page"]]]))

      ;; Otherwise let them login

      (slurp "style/examples/login.html")
      #_[:div
       [:form {:method "POST" :action "/_site/login"}
        (slurp "")
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
