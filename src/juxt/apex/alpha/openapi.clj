;; Copyright © 2021, JUXT LTD.

(ns juxt.apex.alpha.openapi
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.walk :refer [postwalk]]
   [clojure.pprint :refer [pprint]]
   [crux.api :as crux]
   [juxt.jinx.alpha :as jinx]
   [juxt.jinx.alpha.api :as jinx.api]
   [hiccup.core :as h]
   [hiccup.page :as hp]
   [integrant.core :as ig]
   [juxt.apex.alpha :as apex]
   [juxt.apex.alpha.parameters :refer [extract-params-from-request]]
   [juxt.site.alpha :as site]
   [juxt.site.alpha.payload :refer [generate-representation-body]]
   [juxt.site.alpha.perf :refer [fast-get-in]]
   [juxt.site.alpha.put :refer [put-representation]]
   [juxt.jinx.alpha.vocabularies.transformation :refer [process-transformations]]
   [juxt.jinx.alpha.vocabularies.keyword-mapping :refer [process-keyword-mappings]]
   [juxt.site.alpha.response :as response]
   [juxt.site.alpha.util :as util]
   [juxt.site.alpha.entity :as entity]
   [juxt.spin.alpha :as spin]
   [jsonista.core :as json])
  (:import
   (java.net URI)
   (java.util Date UUID)))

;; TODO: Restrict where openapis can be PUT
(defn locate-resource [request db]
  ;; Do we have any OpenAPIs in the database?
  (or

   ;; The OpenAPI document
   (when (and (re-matches #"/_crux/apis/\w+/openapi.json" (:uri request))
              (not (.endsWith (:uri request) "/")))

     (or
      ;; It might exist
      (crux/entity db (URI. (:uri request)))

      ;; Or it might not
      ;; This last item (:put) might be granted by the PDP.
      {::site/description
       "Resource with no representations accepting a PUT of an OpenAPI JSON document."
       ::spin/methods #{:get :head :options :put}
       ::spin/acceptable {"accept" "application/vnd.oai.openapi+json;version=3.0.2"}}))

   (let [abs-request-path (.getPath (URI. (:uri request)))]
     (when-let [{:keys [openapi-ent openapi rel-request-path]}
                (some
                 (fn [[openapi-eid openapi]]
                   (some
                    (fn [server]
                      (let [server-url (get server "url")]
                        (when (.startsWith abs-request-path server-url)
                          {:openapi-eid openapi-eid
                           :openapi openapi
                           :rel-request-path (subs abs-request-path (count server-url))})))
                    (get openapi "servers")))
                 ;; Iterate across all APIs in this server, looking for a match
                 (crux/q db '{:find [openapi-eid openapi]
                              :where [[openapi-eid :juxt.apex.alpha/openapi openapi]]}))]

       ;; Yes?
       (let [paths (get openapi "paths")]
         ;; Any of these paths match the request's URL?
         (some
          (fn [[path path-item-object]]
            (let [path-params
                  (->>
                   (or
                    (get-in path-item-object [(name (:request-method request)) "parameters"])
                    (get-in path-item-object ["parameters"]))
                   (filter #(= (get % "in") "path")))

                  pattern
                  (re-pattern
                   (str/replace
                    path
                    #"\{(\p{Alpha}+)\}"
                    (fn [[_ group]]
                      (format "(?<%s>\\w+)" group))))

                  matcher (re-matcher pattern rel-request-path)]

              (when (.find matcher)
                (let [path-params
                      (into
                       {}
                       (for [param path-params
                             :let [param-name (get param "name")]]
                         [param-name (.group matcher param-name)]))

                      operation-object (get path-item-object (name (:request-method request)))]

                  {:description "OpenAPI matched path"
                   ::apex/openid-path path
                   ::apex/openid-path-params path-params
                   ::spin/methods
                   (keep
                    #{:get :head :post :put :delete :options :trace :connect}
                    (let [methods (set
                                   (conj (map keyword (keys path-item-object)) :options))]
                      (cond-> methods
                        (contains? methods :get)
                        (conj :head))))

                   ::spin/representations
                   (for [[media-type media-type-object]
                         (fast-get-in path-item-object ["get" "responses" "200" "content"])]
                     {::spin/content-type media-type
                      ::spin/last-modified (java.util.Date.)
                      ::spin/bytes-generator ::entity-bytes-generator
                      })

                   ::apex/operation operation-object

                   ;; This is useful, because it is the base document for any
                   ;; relative json pointers.
                   ::apex/openapi openapi}))))

          paths))))))

(defn ->query [input params]
  (let [input (postwalk (fn [x]
                      (if (and (map? x)
                               (contains? x "name")
                               (= (get x "in") "query"))
                        (get-in params [:query (get x "name") :value]
                                (get-in params [:query (get x "name") :param "default"]))
                        x))
                        input)]
    (reduce
     (fn [acc [k v]]
       (assoc acc (keyword k)
              (case (keyword k)
                :find (mapv symbol v)
                :where (mapv (fn [clause]
                               (cond
                                 (and (vector? clause) (every? (comp not coll?) clause))
                                 (mapv (fn [item txf] (txf item)) clause [symbol keyword symbol])

                                 ;;(and (vector? clause) (list? (first clause)))
                                 ;;(mapv (fn [item txf] (txf item)) clause [#(fn ) symbol])
                                 ))

                             v)

                :limit v
                :in (mapv symbol v)
                :args [(reduce-kv (fn [acc k v] (assoc acc (keyword k) v)) {} (first v))]
                )))
     {} input)))



#_(defn uri [x] (java.net.URI. x))

#_(let [path-params {"id" "owners"}
        path (java.net.URI. "/_crux/pass/user-groups/owners")]
  (crux/q
   (dev/db)
   '{:find [(eql/project e [*])]
     :where [[e :crux.db/id $path]]
     :in [$path]}
   path))

;; Possibly promote up into site - by default we output the resource state, but
;; there may be a better rendering of collections, which can be inferred from
;; the schema being an array and use the items subschema. We can also use the
;; resource state as a
(defmethod generate-representation-body ::entity-bytes-generator [request resource representation db]

  (let [param-defs
        (get-in resource [:juxt.apex.alpha/operation "parameters"])

        query
        (get-in resource [:juxt.apex.alpha/operation "responses" "200" "crux/query"])

        crux-query
        (when query (->query query (extract-params-from-request request param-defs)))

        ;;_ (pprint query)
        ;;_ (pprint (->query query))

        #_[path-params {"id" "owners"}
           path (java.net.URI. "/_crux/pass/user-groups/owners")]
        ;;path (java.net.URI. )

        resource-state
        (if query
          (for [[e] (crux/q db
                            crux-query
                            ;; Could put some in params here
                            )]
            (crux/entity db e))
          (crux/entity db (java.net.URI. (:uri request))))

        ]
    ;; TODO: Might want to filter out the spin metadata at some point
    (case (::spin/content-type representation)
      "application/json"
      ;; TODO: Might want to filter out the spin metadata at some point
      (json/write-value-as-bytes (->query query (extract-params-from-request request param-defs)))

      "text/html;charset=utf-8"
      (let [config (get-in resource [:juxt.apex.alpha/operation "responses" "200" "content" (::spin/content-type representation)])
            ]
        (.getBytes

         ;; We can get config from openapi
         (hp/html5
          [:h1 (get config "title" "No title")]

          ;; Get :path-params = {"id" "owners"}

          (cond
            (= (get config "type") "table")
            (if (seq resource-state)
              (let [fields (distinct (concat [:crux.db/id] (keys (first resource-state))))]
                [:table {:style "border: 1px solid #888; border-collapse: collapse; "}
                 [:thead
                  [:tr
                   (for [field fields]
                     [:th {:style "border: 1px solid #888; padding: 4pt; text-align: left"} (pr-str field)])]]
                 [:tbody
                  (for [row resource-state]
                    [:tr
                     (for [field fields
                           :let [val (get row field)]]
                       [:td {:style "border: 1px solid #888; padding: 4pt; text-align: left"}
                        (cond
                          (uri? val)
                          [:a {:href val} val]
                          :else
                          (get row field))])])]])
              [:p "No results"])

            :else
            (let [fields (distinct (concat [:crux.db/id] (keys resource-state)))]
              [:dl
               (for [field fields
                     :let [val (get resource-state field)]]
                 (list
                  [:dt
                   (pr-str field)]
                  [:dd
                   (cond
                     (uri? val)
                     [:a {:href val} val]
                     :else
                     (get resource-state field))]))]))

          [:h2 "Debug"]
          [:h3 "Resource"]
          [:pre (with-out-str (pprint resource))]
          (when query
            (list
             [:h3 "Query"]
             [:pre (with-out-str (pprint query))]))
          (when crux-query
            (list
             [:h3 "Crux Query"]
             [:pre (with-out-str (pprint (->query query (extract-params-from-request request param-defs))))]))

          (when (seq param-defs)
            (list
             [:h3 "Parameters"]
             [:pre (with-out-str (pprint (extract-params-from-request request param-defs)))]))

          [:h3 "Resource state"]
          [:pre (with-out-str (pprint resource-state))]))))))

(defmethod generate-representation-body ::api-console-generator [request resource representation db]
  (.getBytes
   (.toString
    (doto (StringBuilder.)
      (.append "<h1>API Console</h1>\r\n")
      (.append "<ul>")
      (.append
       (apply str
              (for [[uri openapi]
                    (crux/q db '{:find [e openapi]
                                 :where [[e ::apex/openapi openapi]]})]
                (str
                 "<li>" (get-in openapi ["info" "title"])
                 "&nbsp<small>[&nbsp;"
                 (format "<a href='/_crux/swagger-ui/index.html?url=%s'>" uri)
                 "Swagger UI"
                 "&nbsp;]</small>"
                 "</a></li>"))))
      (.append "</ul>")))))


(defmethod put-representation
  "application/vnd.oai.openapi+json;version=3.0.2"
  [request _ openapi-json-representation old-representation crux-node]

  (let [uri (URI. (:uri request))
        last-modified (java.util.Date.)
        openapi (json/read-value (java.io.ByteArrayInputStream. (::spin/bytes openapi-json-representation)))
        etag (format "\"%s\"" (subs (util/hexdigest (.getBytes (pr-str openapi))) 0 32))]
    (crux/submit-tx
     crux-node
     [
      [:crux.tx/put
       {:crux.db/id uri

        ;; Resource configuration
        ::spin/methods #{:get :head :put :options}
        ::spin/representations
        [(assoc
          openapi-json-representation
          ::spin/etag etag
          ::spin/last-modified last-modified)]

        ;; Resource state
        ::apex/openapi openapi}]])

    {:status 201
     ;; TODO: Add :body to describe the new resource
     }))

(defmethod put-representation
  "application/json"
  [request resource new-representation old-representation crux-node]

  (let [date (java.util.Date.)
        last-modified date
        etag (format "\"%s\"" (subs (util/hexdigest (::spin/bytes new-representation)) 0 32))
        representation-metadata {::spin/etag etag
                                 ::spin/last-modified last-modified}
        schema (get-in resource [::apex/operation "requestBody" "content" "application/json" "schema"])
        _ (assert schema)
        instance (-> (json/read-value (::spin/bytes new-representation))
                     ;; If we don't add the id, we'll fail the schema validation
                     ;; check
                     (assoc "id" (:uri request)))
        _ (assert instance)
        openapi (:juxt.apex.alpha/openapi resource)
        _ (assert openapi)
        validation-results (jinx.api/validate schema instance {:base-document openapi})
        ]

    ;; TODO: extract the title/version of the API and add to the entity (as metadata)
    #_(let [openapi (crux/entity (crux/db crux-node) (::apex/!api resource))]
        (prn "version of open-api used to put this resource is"
             (get-in openapi [::apex/openapi "info" "version"])))

    ;; TODO: Validate new-representation against the JSON schema in the openapi.

    (println "Post validation, before post-processing")
    (pprint (update new-representation ::spin/bytes #(String. %)))

    (when-not (::jinx/valid? validation-results)
      (pprint validation-results)
      (throw
       (ex-info
        "Schema validation failed"
        {::spin/response
         {:status 400
          ;; TODO: Content negotiation for error responses
          :body (with-out-str (pprint validation-results))}})))


    (let [validation (-> validation-results process-transformations process-keyword-mappings)
          instance (::jinx/instance validation)]

      (assert (:crux.db/id instance) "The doc must contain an entry for :crux.db/id")

      (println "Instance, ready to submit is:")
      (pprint instance)

      ;; Since this resource is 'managed' by the locate-resource in this ns, we
      ;; don't have to worry about spin attributes - these will be provided by
      ;; the locate-resource function. We just need the resource state here.
      (crux/submit-tx
       crux-node
       [[:crux.tx/put instance]])

      (spin/response
       (if old-representation 200 201)
       (response/representation-metadata-headers
        (merge
         representation-metadata
         ;; TODO: Source this from the openapi, proactively content-negotiation if
         ;; multiple possible (for each of 200 and 201)
         {::spin/content-type "application/json"}))
       nil
       request
       nil
       date
       (json/write-value-as-bytes instance)))))

;; TODO: This can be PUT instead of being built-in.
(defn swagger-ui []
  (let [jarpath
        (some
         #(re-matches #".*swagger-ui.*" %)
         (str/split (System/getProperty "java.class.path") #":"))
        fl (io/file jarpath)
        jar (java.util.jar.JarFile. fl)]
    (doall
     (for [je (enumeration-seq (.entries jar))
           :let [nm (.getRealName je)
                 [_ suffix] (re-matches #".*\.(.*)" nm)
                 size (.getSize je)
                 bytes (byte-array size)
                 path (second
                       (re-matches #"META-INF/resources/webjars/swagger-ui/[0-9.]+/(.*)"
                                   nm))]
           :when path
           :let [uri (URI. (format "/_crux/swagger-ui/%s" path))]
           :when (pos? size)]
       (do
         (.read (.getInputStream jar je) bytes 0 size)
         [:crux.tx/put
          {:crux.db/id uri
           ::spin/methods #{:get :head :options}
           ::spin/representations [{::spin/content-type (get util/mime-types suffix "application/octet-stream")
                                    ::spin/last-modified (java.util.Date. (.getTime je))
                                    ::spin/content-length size
                                    ::spin/content-location uri
                                    ::spin/bytes bytes}]}])))))


(defn api-console []
  [[:crux.tx/put
    {:crux.db/id (URI. "/_crux/api-console")
     ::spin/methods #{:get :head :options}
     ::spin/representations
     [{::spin/content-type "text/html;charset=utf-8"
       ::spin/bytes-generator ::api-console-generator}]}]])

(defmethod ig/init-key ::module [_ {:keys [crux]}]
  (println "Adding OpenAPI module")
  (crux/submit-tx
   crux
   (concat
    ;; This should be possible to upload
    (swagger-ui)
    ;; This needs an api-console-generator, so not sure it can be uploaded
    (api-console))))



(jinx.api/validate
 (jinx.api/schema {"const" "foo"})
 "fooj")
