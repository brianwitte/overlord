(ns owasp-proxy.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]])
  (:import [java.net ServerSocket Socket InetSocketAddress Proxy Proxy$Type]
           [java.io BufferedReader InputStreamReader PrintWriter OutputStream]
           [javax.net.ssl SSLContext SSLSocketFactory HttpsURLConnection]
           [java.security.cert X509Certificate]
           [javax.net.ssl X509TrustManager HostnameVerifier]
           [java.util.concurrent Executors]
           [java.net.http HttpClient HttpRequest HttpResponse HttpRequest$Builder]
           [java.net URI]
           [java.time Duration]))

;; Configuration
(def config-file "owasp.edn")

(def default-config
  {:proxy {:port 8080
           :host "localhost"}
   :ssl {:trust-all true
         :verify-hostnames false}
   :rules {:modify-requests []
           :modify-responses []
           :security-checks {:check-headers true
                           :check-ssl true
                           :check-injection true}}
   :logging {:level :info
            :file "owasp-proxy.log"}})

;; Security Checks
(defn check-security-headers [headers]
  "Check for missing security headers"
  (let required-headers ["x-frame-options" "x-content-type-options" 
                        "x-xss-protection" "strict-transport-security"
                        "content-security-policy"]
        missing-headers (filter #(not (contains? headers (str/lower-case %))) 
                               required-headers)]
    (when (seq missing-headers)
      {:type :missing-security-headers
       :severity :medium
       :missing missing-headers})))

(defn check-ssl-issues [url headers]
  "Check for SSL/TLS security issues"
  (let issues []]
    (cond-> issues
      (not (str/starts-with? url "https://"))
      (conj {:type :insecure-protocol :severity :high :details "Using HTTP instead of HTTPS"})
      
      (not (get headers "strict-transport-security"))
      (conj {:type :missing-hsts :severity :medium :details "Missing HSTS header"}))))

(defn check-injection-patterns [content]
  "Basic injection pattern detection"
  (let patterns {"sql-injection" #"(?i)(union|select|insert|update|delete|drop|exec|script)"
                 "xss" #"(?i)(<script|javascript:|on\w+\s*=)"
                 "path-traversal" #"\.\./"
                 "ldap-injection" #"\*|\(|\)|&|\|"}
        findings (for [[type pattern] patterns
                      :when (re-find pattern (str content))]
                  {:type (keyword (str "potential-" type))
                   :severity :high
                   :pattern (str pattern)})]
    (seq findings)))

;; Configuration Management
(defn load-config []
  (if (.exists (io/file config-file))
    (merge default-config (edn/read-string (slurp config-file)))
    (do
      (spit config-file (with-out-str (pprint default-config)))
      (println "Created default config file:" config-file)
      default-config)))

;; Request/Response Modification
(defn apply-rules [data rules rule-type]
  "Apply modification rules to request/response data"
  (reduce (fn [acc rule]
            (case (:action rule)
              :add-header (assoc-in acc [:headers (:header rule)] (:value rule))
              :remove-header (update acc :headers dissoc (:header rule))
              :modify-body (assoc acc :body (str/replace (:body acc) 
                                                        (:pattern rule) 
                                                        (:replacement rule)))
              :add-param (assoc-in acc [:params (:param rule)] (:value rule))
              acc))
          data
          (get rules rule-type [])))

;; SSL/TLS Configuration
(defn create-trust-all-manager []
  (reify X509TrustManager
    (checkClientTrusted [_ _ _])
    (checkServerTrusted [_ _ _])
    (getAcceptedIssuers [_] nil)))

(defn create-hostname-verifier []
  (reify HostnameVerifier
    (verify [_ _ _] true)))

(defn setup-ssl-context [trust-all?]
  (when trust-all?
    (let [trust-manager (create-trust-all-manager)
          ssl-context (SSLContext/getInstance "TLS")]
      (.init ssl-context nil (into-array [trust-manager]) nil)
      (HttpsURLConnection/setDefaultSSLSocketFactory (.getSocketFactory ssl-context))
      (HttpsURLConnection/setDefaultHostnameVerifier (create-hostname-verifier)))))

;; HTTP Client
(defn create-http-client [config]
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 30))
      (.build)))

;; Request Processing
(defn parse-http-request [request-str]
  "Parse HTTP request string into map"
  (let [lines (str/split-lines request-str)
        [method path version] (str/split (first lines) #"\s+")
        header-lines (take-while #(not (str/blank? %)) (rest lines))
        headers (into {} (map #(let [[k v] (str/split % #":\s*" 2)]
                                [(str/lower-case k) v]) header-lines))
        body-start (+ 1 (count header-lines) 1)
        body (str/join "\n" (drop body-start lines))]
    {:method method
     :path path
     :version version
     :headers headers
     :body body}))

(defn build-http-request [req-map target-host]
  "Build HttpRequest from parsed request map"
  (let [uri (URI. (str "https://" target-host (:path req-map)))
        builder (-> (HttpRequest/newBuilder uri)
                    (.method (:method req-map) 
                            (if (seq (:body req-map))
                              (HttpRequest$BodyPublishers/ofString (:body req-map))
                              (HttpRequest$BodyPublishers/noBody))))]
    (doseq [[k v] (:headers req-map)]
      (when (not= k "host")
        (.header builder k v)))
    (.build builder)))

;; Security Analysis
(defn analyze-request [req-data config]
  "Perform security analysis on request"
  (let checks (:security-checks (:rules config))
        findings []]
    (cond-> findings
      (:check-injection checks)
      (concat (or (check-injection-patterns (:body req-data)) []))
      
      (:check-headers checks)
      (concat (or (check-security-headers (:headers req-data)) [])))))

(defn analyze-response [resp-data url config]
  "Perform security analysis on response"
  (let checks (:security-checks (:rules config))
        findings []]
    (cond-> findings
      (:check-headers checks)
      (concat (or (check-security-headers (:headers resp-data)) []))
      
      (:check-ssl checks)
      (concat (check-ssl-issues url (:headers resp-data)))
      
      (:check-injection checks)
      (concat (or (check-injection-patterns (:body resp-data)) [])))))

;; Logging
(defn log-finding [finding req-data resp-data]
  "Log security finding"
  (println (format "[%s] %s - %s" 
                  (str/upper-case (name (:severity finding)))
                  (name (:type finding))
                  (or (:details finding) (:missing finding) (:pattern finding)))))

(defn log-transaction [req-data resp-data findings]
  "Log HTTP transaction and findings"
  (println (format "%s %s - Status: %s" 
                  (:method req-data) 
                  (:path req-data)
                  (get-in resp-data [:status] "Unknown")))
  (doseq [finding findings]
    (log-finding finding req-data resp-data)))

;; Proxy Handler
(defn handle-client [client-socket config http-client]
  "Handle individual client connection"
  (try
    (with-open [in (BufferedReader. (InputStreamReader. (.getInputStream client-socket)))
                out (PrintWriter. (.getOutputStream client-socket) true)]
      (let request-lines (loop [lines []]
                          (let [line (.readLine in)]
                            (if (or (nil? line) (str/blank? line))
                              lines
                              (recur (conj lines line)))))
            request-str (str/join "\n" request-lines)
            req-data (parse-http-request request-str)]
        
        (when (seq request-str)
          ;; Apply request modifications
          (let modified-req (apply-rules req-data (:rules config) :modify-requests)
                target-host (get (:headers modified-req) "host")
                findings-req (analyze-request modified-req config)]
            
            (try
              ;; Forward request
              (let http-req (build-http-request modified-req target-host)
                    response (.send http-client http-req (HttpResponse$BodyHandlers/ofString))
                    resp-headers (into {} (.map (.headers response)))
                    resp-data {:status (.statusCode response)
                              :headers resp-headers
                              :body (.body response)}
                    modified-resp (apply-rules resp-data (:rules config) :modify-responses)
                    findings-resp (analyze-response modified-resp 
                                                   (str "https://" target-host (:path req-data))
                                                   config)
                    all-findings (concat findings-req findings-resp)]
                
                ;; Send response back to client
                (.println out (format "HTTP/1.1 %d OK" (:status modified-resp)))
                (doseq [[k v] (:headers modified-resp)]
                  (.println out (format "%s: %s" k v)))
                (.println out "")
                (.print out (:body modified-resp))
                (.flush out)
                
                ;; Log transaction and findings
                (log-transaction req-data resp-data all-findings))
              
              (catch Exception e
                (.println out "HTTP/1.1 502 Bad Gateway")
                (.println out "")
                (.println out "Proxy Error")
                (println "Error forwarding request:" (.getMessage e))))))))
    (catch Exception e
      (println "Error handling client:" (.getMessage e)))
    (finally
      (.close client-socket))))

;; Main Proxy Server
(defn start-proxy [config]
  "Start the OWASP security proxy server"
  (let proxy-port (get-in config [:proxy :port])
        proxy-host (get-in config [:proxy :host])
        http-client (create-http-client config)
        server-socket (ServerSocket. proxy-port)
        executor (Executors/newFixedThreadPool 10)]
    
    (setup-ssl-context (get-in config [:ssl :trust-all]))
    
    (println (format "OWASP Security Proxy started on %s:%d" proxy-host proxy-port))
    (println "Configure your browser to use this proxy for HTTP/HTTPS traffic")
    (println "Press Ctrl+C to stop")
    
    (try
      (while true
        (let client-socket (.accept server-socket)]
          (.submit executor #(handle-client client-socket config http-client))))
      (catch Exception e
        (println "Server error:" (.getMessage e)))
      (finally
        (.close server-socket)
        (.shutdown executor)))))

;; CLI Interface
(defn print-usage []
  (println "OWASP Security Proxy Tool")
  (println "Usage:")
  (println "  clojure owasp-proxy.clj start    - Start the proxy server")
  (println "  clojure owasp-proxy.clj config   - Show current configuration")
  (println "  clojure owasp-proxy.clj help     - Show this help"))

(defn show-config [config]
  (println "Current Configuration:")
  (pprint config))

(defn -main [& args]
  (let config (load-config)
        command (first args)]
    (case command
      "start" (start-proxy config)
      "config" (show-config config)
      "help" (print-usage)
      nil (print-usage)
      (do
        (println "Unknown command:" command)
        (print-usage)))))

;; Run if called directly
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
