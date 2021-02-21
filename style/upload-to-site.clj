#_( ;; Allow this script to be executed directly
   "exec" "bb" "--classpath" "$(clojure -Spath -Sdeps '{:deps {io.aviso/pretty {:mvn/version "RELEASE"}}}')" "$0" "$@"
   )

(ns upload-to-site
  (:require
   [babashka.curl :as curl]
   [io.aviso.ansi :as ansi]
   [clojure.java.io :as io]))

(defmacro with-status-message [msg & body]
  `(do
     (print (str ansi/yellow-font ~msg ansi/reset-font "..."))
     (let [result# ~@body]
       (println (str "[" ansi/bold-red-font "done" ansi/reset-font "]"))
       result#)))

(doseq [fl (take 1 (.listFiles (io/file "node_modules/tailwindcss/dist")))]
  (with-status-message
    (format "PUT resource")
    (curl/put
     (str "http://localhost:8082/css/" (.getName fl))
     {:headers {"content-type" "text/css"}
      :throw true
      :body fl
      :basic-auth ["webmaster" "FunkyForest"]
      :debug false})))


;; Local Variables:
;; mode: clojure
;; End:
