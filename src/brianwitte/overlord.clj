(ns brianwitte.overlord
  "Overlord daemon - A web service daemon using Integrant, Reitit, and Malli"
  (:require [integrant.core :as ig]
            [reitit.ring :as ring]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.ring.coercion :as coercion]
            [reitit.coercion.malli :as rcm]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.cors :as cors]
            [muuntaja.core :as m]
            [malli.core :as malli]
            [malli.transform :as mt]
            [clojure.tools.logging :as log]
            [clojure.pprint :as pp])
  (:gen-class))

;; =============================================================================
;; Schemas
;; =============================================================================

(def task-schema
  [:map
   [:id :uuid]
   [:name :string]
   [:status [:enum "pending" "running" "completed" "failed"]]
   [:created-at inst?]
   [:updated-at {:optional true} inst?]])

(def create-task-schema
  [:map
   [:name :string]
   [:description {:optional true} :string]])

;; =============================================================================
;; Database (In-memory for demo)
;; =============================================================================

(defonce task-db (atom {}))

(defn create-task! [task-data]
  (let [id (random-uuid)
        task (merge task-data
                   {:id id
                    :status "pending"
                    :created-at (java.time.Instant/now)})]
    (swap! task-db assoc id task)
    task))

(defn get-task [id]
  (get @task-db id))

(defn list-tasks []
  (vals @task-db))

(defn update-task-status! [id status]
  (when (get @task-db id)
    (swap! task-db assoc-in [id :status] status)
    (swap! task-db assoc-in [id :updated-at] (java.time.Instant/now))
    (get @task-db id)))

;; =============================================================================
;; Handlers
;; =============================================================================

(defn health-handler [_request]
  {:status 200
   :body {:status "healthy"
          :timestamp (java.time.Instant/now)
          :service "overlord"}})

(defn list-tasks-handler [_request]
  {:status 200
   :body {:tasks (list-tasks)}})

(defn create-task-handler [{{:keys [body]} :parameters}]
  (try
    (let [task (create-task! body)]
      (log/info "Created task:" (:id task))
      {:status 201
       :body {:task task}})
    (catch Exception e
      (log/error e "Failed to create task")
      {:status 500
       :body {:error "Failed to create task"}})))

(defn get-task-handler [{{:keys [path]} :parameters}]
  (let [{:keys [id]} path]
    (if-let [task (get-task (parse-uuid id))]
      {:status 200
       :body {:task task}}
      {:status 404
       :body {:error "Task not found"}})))

(defn update-task-status-handler [{{:keys [path body]} :parameters}]
  (let [{:keys [id]} path
        {:keys [status]} body]
    (if-let [task (update-task-status! (parse-uuid id) status)]
      {:status 200
       :body {:task task}}
      {:status 404
       :body {:error "Task not found"}})))

;; =============================================================================
;; Routes
;; =============================================================================

(def routes
  ["/api/v1"
   ["/health" {:get health-handler}]
   ["/tasks"
    ["" {:get list-tasks-handler
         :post {:handler create-task-handler
                :parameters {:body create-task-schema}}}]
    ["/:id"
     ["" {:get {:handler get-task-handler
                :parameters {:path [:map [:id :string]]}}}]
     ["/status" {:put {:handler update-task-status-handler
                       :parameters {:path [:map [:id :string]]
                                   :body [:map [:status :string]]}}}]]]])

;; =============================================================================
;; Middleware
;; =============================================================================

(defn wrap-logging [handler]
  (fn [request]
    (let [start (System/currentTimeMillis)
          response (handler request)
          duration (- (System/currentTimeMillis) start)]
      (log/info (format "%s %s -> %d (%dms)"
                       (:request-method request)
                       (:uri request)
                       (:status response)
                       duration))
      response)))

;; =============================================================================
;; Application
;; =============================================================================

(defn create-app []
  (ring/ring-handler
    (ring/router
      routes
      {:data {:coercion rcm/coercion
              :muuntaja m/instance
              :middleware [parameters/parameters-middleware
                          muuntaja/format-negotiate-middleware
                          muuntaja/format-response-middleware
                          muuntaja/format-request-middleware
                          coercion/coerce-response-middleware
                          coercion/coerce-request-middleware]}})
    (ring/routes
      (ring/create-default-handler
        {:not-found (constantly {:status 404 :body {:error "Not found"}})}))
    {:middleware [wrap-logging
                  [cors/wrap-cors
                   :access-control-allow-origin [#".*"]
                   :access-control-allow-methods [:get :put :post :delete]]]}))

;; =============================================================================
;; Integrant Configuration
;; =============================================================================

(def config
  {:overlord/app {}
   :overlord/server {:app (ig/ref :overlord/app)
                     :port 8080
                     :host "0.0.0.0"}
   :overlord/daemon {:server (ig/ref :overlord/server)}})

;; =============================================================================
;; Integrant Methods
;; =============================================================================

(defmethod ig/init-key :overlord/app [_ _]
  (log/info "Initializing application...")
  (create-app))

(defmethod ig/halt-key! :overlord/app [_ _]
  (log/info "Shutting down application..."))

(defmethod ig/init-key :overlord/server [_ {:keys [app port host]}]
  (log/info (format "Starting server on %s:%d" host port))
  (jetty/run-jetty app {:port port
                       :host host
                       :join? false}))

(defmethod ig/halt-key! :overlord/server [_ server]
  (log/info "Stopping server...")
  (.stop server))

(defmethod ig/init-key :overlord/daemon [_ {:keys [server]}]
  (log/info "Daemon initialized with server:" server)
  {:server server
   :started-at (java.time.Instant/now)})

(defmethod ig/halt-key! :overlord/daemon [_ daemon]
  (log/info "Daemon shutting down...")
  (pp/pprint daemon))

;; =============================================================================
;; System Management
;; =============================================================================

(defonce system (atom nil))

(defn start-system! []
  (when @system
    (ig/halt! @system))
  (reset! system (ig/init config))
  (log/info "Overlord daemon started!"))

(defn stop-system! []
  (when @system
    (ig/halt! @system)
    (reset! system nil)
    (log/info "Overlord daemon stopped!")))

(defn restart-system! []
  (stop-system!)
  (start-system!))

;; =============================================================================
;; Signal Handlers
;; =============================================================================

(defn setup-shutdown-hook! []
  (.addShutdownHook (Runtime/getRuntime)
                   (Thread. #(do
                              (log/info "Received shutdown signal...")
                              (stop-system!)))))

;; =============================================================================
;; Public API for exec-fn
;; =============================================================================

(defn start-daemon 
  "Entry point for clj -X execution"
  [_opts]
  (-main))

;; =============================================================================
;; Main
;; =============================================================================

(defn -main [& args]
  (log/info "Starting Overlord daemon...")
  (setup-shutdown-hook!)
  (start-system!)
  
  ;; Keep the main thread alive
  (loop []
    (Thread/sleep 1000)
    (when @system
      (recur)))
  
  (log/info "Overlord daemon exiting..."))

;; =============================================================================
;; REPL Helpers
;; =============================================================================

(comment
  ;; Start the system
  (start-system!)
  
  ;; Stop the system  
  (stop-system!)
  
  ;; Restart the system
  (restart-system!)
  
  ;; Check system status
  @system
  
  ;; Test the API
  (require '[clj-http.client :as http])
  (require '[cheshire.core])
  
  ;; Health check
  (http/get "http://localhost:8080/api/v1/health"
            {:accept :json :as :json})
  
  ;; Create a task
  (http/post "http://localhost:8080/api/v1/tasks"
             {:content-type :json
              :accept :json
              :as :json
              :body (cheshire.core/generate-string {:name "Test task"
                                                   :description "A test task"})})
  
  ;; List tasks
  (http/get "http://localhost:8080/api/v1/tasks"
            {:accept :json :as :json})
  
  ;; Check task database
  @task-db
  
  ;; Clear task database
  (reset! task-db {})
  
  )
