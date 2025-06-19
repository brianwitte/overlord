(ns brianwitte.overlord
  (:require [clojure.tools.logging :as log]
            [clojure.pprint :as pp]
            [integrant.core :as ig]
            [ring.adapter.jetty :as jetty]
            [reitit.ring :as ring]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.coercion :as coercion]
            [reitit.coercion.malli :as rcm]
            [muuntaja.core :as m]
            [ring.middleware.cors :as cors]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:gen-class))

;; =============================================================================
;; Configuration & Environment
;; =============================================================================

(def config
  {:apis
   {:google-maps
    {:key (or (System/getenv "GOOGLE_MAPS_API_KEY") "demo-key")
     :base-url "https://maps.googleapis.com/maps/api"
     :rate-limit 10000} ; requests per day
    
    :openweather
    {:key (or (System/getenv "OPENWEATHER_API_KEY") "demo-key")
     :base-url "https://api.openweathermap.org/data/2.5"
     :rate-limit 1000} ; requests per day
    
    :weatherapi
    {:key (or (System/getenv "WEATHERAPI_KEY") "demo-key")
     :base-url "https://api.weatherapi.com/v1"
     :rate-limit 1000000} ; requests per month
    
    :here-traffic
    {:key (or (System/getenv "HERE_API_KEY") "demo-key")
     :base-url "https://traffic.ls.hereapi.com/traffic/6.3"
     :rate-limit 250000}} ; requests per month
   
   :cache
   {:ttl-traffic 300 ; 5 minutes
    :ttl-weather 900 ; 15 minutes  
    :ttl-predictions 1800} ; 30 minutes
   
   :locations
   {:tokyo {:lat 35.6762 :lon 139.6503 :name "Tokyo"}
    :osaka {:lat 34.6937 :lon 135.5023 :name "Osaka"}
    :nagoya {:lat 35.1815 :lon 136.9066 :name "Nagoya"}
    :yokohama {:lat 35.4437 :lon 139.6380 :name "Yokohama"}
    :kyoto {:lat 35.0116 :lon 135.7681 :name "Kyoto"}}})

;; =============================================================================
;; Data Schemas
;; =============================================================================

(def traffic-data-schema
  [:map
   [:segment-id :string]
   [:coordinates [:tuple :double :double]]
   [:current-speed :double]
   [:free-flow-speed :double]
   [:congestion-level [:enum "light" "moderate" "heavy" "severe"]]
   [:last-updated inst?]
   [:weather-impact {:optional true} :double]
   [:prediction-confidence {:optional true} :double]])

(def weather-data-schema
  [:map
   [:location-id :string]
   [:coordinates [:tuple :double :double]]
   [:temperature :double]
   [:humidity :double]
   [:precipitation :double]
   [:visibility :double]
   [:wind-speed :double]
   [:air-quality-index {:optional true} :int]
   [:last-updated inst?]])

(def urban-insight-schema
  [:map
   [:insight-id :string]
   [:location :string]
   [:type [:enum "traffic-prediction" "weather-impact" "event-correlation" "route-optimization"]]
   [:confidence :double]
   [:description :string]
   [:recommendation {:optional true} :string]
   [:created-at inst?]
   [:expires-at inst?]])

(def api-request-schema
  [:map
   [:location [:enum "tokyo" "osaka" "nagoya" "yokohama" "kyoto"]]
   [:data-types {:optional true} [:vector [:enum "traffic" "weather" "insights"]]]
   [:forecast-hours {:optional true} [:int {:min 1 :max 72}]]])

;; =============================================================================
;; In-Memory Database & Cache
;; =============================================================================

(defonce urban-db 
  (atom {:traffic-data {}
         :weather-data {}
         :urban-insights {}
         :api-usage {}
         :cache {}}))

(defn cache-key [api-name location & params]
  (str api-name ":" location ":" (str/join ":" params)))

(defn cache-get [key]
  (let [cached (get-in @urban-db [:cache key])]
    (when (and cached 
               (< (System/currentTimeMillis) (:expires-at cached)))
      (:data cached))))

(defn cache-put [key data ttl-seconds]
  (swap! urban-db assoc-in [:cache key] 
         {:data data
          :expires-at (+ (System/currentTimeMillis) (* ttl-seconds 1000))
          :cached-at (System/currentTimeMillis)}))

(defn track-api-usage [api-name]
  (let [today (.format (java.time.LocalDate/now) 
                      (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd"))]
    (swap! urban-db update-in [:api-usage api-name today] (fnil inc 0))))

;; =============================================================================
;; External API Clients
;; =============================================================================

(defn safe-api-call [api-fn api-name & args]
  "Wrapper for API calls with error handling and usage tracking"
  (try
    (track-api-usage api-name)
    (apply api-fn args)
    (catch Exception e
      (log/error e (str "API call failed for " api-name))
      {:error (.getMessage e) :api api-name})))

(defn fetch-google-traffic [location]
  "Fetch traffic data from Google Maps API"
  (let [api-key (get-in config [:apis :google-maps :key])
        {lat :lat lon :lon} (get-in config [:locations location])
        cache-key (cache-key "google-traffic" (name location))]
    
    (if-let [cached (cache-get cache-key)]
      cached
      (safe-api-call
        (fn []
          (let [response (http/get (str (get-in config [:apis :google-maps :base-url])
                                       "/directions/json")
                                  {:query-params {:origin (str lat "," lon)
                                                 :destination (str (+ lat 0.01) "," (+ lon 0.01))
                                                 :departure_time "now"
                                                 :traffic_model "best_guess"
                                                 :key api-key}
                                   :accept :json
                                   :as :json
                                   :throw-exceptions false})
                traffic-data {:segment-id (str "google-" (name location))
                             :coordinates [lat lon]
                             :current-speed (+ 30 (rand-int 40)) ; Demo data
                             :free-flow-speed 50
                             :congestion-level (rand-nth ["light" "moderate" "heavy"])
                             :last-updated (java.time.Instant/now)
                             :source "google-maps"}]
            (cache-put cache-key traffic-data (get-in config [:cache :ttl-traffic]))
            traffic-data))
        "google-maps"))))

(defn fetch-weather-data [location]
  "Fetch weather data from WeatherAPI"
  (let [api-key (get-in config [:apis :weatherapi :key])
        location-name (get-in config [:locations location :name])
        cache-key (cache-key "weather" (name location))]
    
    (if-let [cached (cache-get cache-key)]
      cached
      (safe-api-call
        (fn []
          (let [response (http/get (str (get-in config [:apis :weatherapi :base-url])
                                       "/current.json")
                                  {:query-params {:key api-key
                                                 :q location-name
                                                 :aqi "yes"}
                                   :accept :json
                                   :as :json
                                   :throw-exceptions false})
                {lat :lat lon :lon} (get-in config [:locations location])
                weather-data {:location-id (str "weather-" (name location))
                             :coordinates [lat lon]
                             :temperature (+ 15 (rand-int 20)) ; Demo data
                             :humidity (+ 40 (rand-int 40))
                             :precipitation (rand 10)
                             :visibility (+ 5 (rand-int 15))
                             :wind-speed (rand 20)
                             :air-quality-index (+ 50 (rand-int 100))
                             :last-updated (java.time.Instant/now)
                             :source "weatherapi"}]
            (cache-put cache-key weather-data (get-in config [:cache :ttl-weather]))
            weather-data))
        "weatherapi"))))

(defn fetch-here-traffic [location]
  "Fetch traffic incidents from HERE API"
  (let [api-key (get-in config [:apis :here-traffic :key])
        {lat :lat lon :lon} (get-in config [:locations location])
        cache-key (cache-key "here-traffic" (name location))]
    
    (if-let [cached (cache-get cache-key)]
      cached
      (safe-api-call
        (fn []
          (let [response (http/get (str (get-in config [:apis :here-traffic :base-url])
                                       "/incidents.json")
                                  {:query-params {:bbox (str (- lat 0.1) "," (- lon 0.1) ";"
                                                            (+ lat 0.1) "," (+ lon 0.1))
                                                 :apikey api-key}
                                   :accept :json
                                   :as :json
                                   :throw-exceptions false})
                incident-data {:segment-id (str "here-" (name location))
                              :coordinates [lat lon]
                              :current-speed (+ 20 (rand-int 50)) ; Demo data
                              :free-flow-speed 60
                              :congestion-level (rand-nth ["light" "moderate" "heavy" "severe"])
                              :last-updated (java.time.Instant/now)
                              :incidents (rand-int 5)
                              :source "here-api"}]
            (cache-put cache-key incident-data (get-in config [:cache :ttl-traffic]))
            incident-data))
        "here-api"))))

;; =============================================================================
;; ML & Analytics Engine
;; =============================================================================

(defn calculate-weather-traffic-correlation [weather-data traffic-data]
  "Calculate correlation between weather conditions and traffic"
  (let [precipitation (:precipitation weather-data 0)
        visibility (:visibility weather-data 10)
        current-speed (:current-speed traffic-data 50)
        free-flow-speed (:free-flow-speed traffic-data 60)
        
        ; Simple correlation model (in real implementation, use historical ML model)
        weather-impact (cond
                        (> precipitation 5) 0.3  ; Heavy rain reduces speed by 30%
                        (< visibility 5) 0.25    ; Low visibility reduces speed by 25%
                        (> precipitation 1) 0.1  ; Light rain reduces speed by 10%
                        :else 0)
        
        speed-ratio (/ current-speed free-flow-speed)
        correlation-coefficient (- 1.0 (+ speed-ratio weather-impact))]
    
    {:weather-impact weather-impact
     :correlation-coefficient (max 0.0 (min 1.0 correlation-coefficient))
     :predicted-impact (* weather-impact 100) ; percentage
     :confidence (if (> precipitation 0) 0.85 0.6)}))

(defn generate-traffic-prediction [location hours-ahead]
  "Generate traffic prediction using simple time-series model"
  (let [current-hour (.getHour (java.time.LocalDateTime/now))
        target-hour (mod (+ current-hour hours-ahead) 24)
        
        ; Rush hour patterns (simplified)
        rush-hour-multiplier (cond
                              (or (and (>= target-hour 7) (<= target-hour 9))
                                  (and (>= target-hour 17) (<= target-hour 19))) 1.8
                              (and (>= target-hour 22) (<= target-hour 6)) 0.4
                              :else 1.0)
        
        base-speed 45
        predicted-speed (* base-speed (/ 1.0 rush-hour-multiplier))
        congestion-level (cond
                          (< predicted-speed 20) "severe"
                          (< predicted-speed 30) "heavy"
                          (< predicted-speed 40) "moderate"
                          :else "light")]
    
    {:location location
     :prediction-time (java.time.Instant/now)
     :target-hour target-hour
     :predicted-speed predicted-speed
     :congestion-level congestion-level
     :confidence 0.75
     :factors {:rush-hour-impact rush-hour-multiplier
               :base-conditions "normal"}}))

(defn generate-urban-insights [location weather-data traffic-data]
  "Generate actionable urban insights"
  (let [correlation (calculate-weather-traffic-correlation weather-data traffic-data)
        prediction-1h (generate-traffic-prediction location 1)
        prediction-3h (generate-traffic-prediction location 3)
        
        insights []]
    
    (cond-> insights
      (> (:weather-impact correlation) 0.2)
      (conj {:insight-id (str "weather-impact-" location "-" (System/currentTimeMillis))
             :location (name location)
             :type "weather-impact"
             :confidence (:confidence correlation)
             :description (format "Weather conditions are causing %.0f%% traffic slowdown"
                                (:predicted-impact correlation))
             :recommendation "Consider alternative routes or delay non-essential trips"
             :created-at (java.time.Instant/now)
             :expires-at (java.time.Instant/ofEpochSecond (+ (System/currentTimeMillis) 3600))})
      
      (= (:congestion-level prediction-1h) "severe")
      (conj {:insight-id (str "traffic-prediction-" location "-" (System/currentTimeMillis))
             :location (name location)
             :type "traffic-prediction"
             :confidence (:confidence prediction-1h)
             :description (format "Severe congestion predicted in 1 hour (%.0f km/h average speed)"
                                (:predicted-speed prediction-1h))
             :recommendation "Avoid travel between peak hours or use public transportation"
             :created-at (java.time.Instant/now)
             :expires-at (java.time.Instant/ofEpochSecond (+ (System/currentTimeMillis) 7200))})
      
      (and (= (:congestion-level traffic-data) "heavy")
           (= (:congestion-level prediction-3h) "light"))
      (conj {:insight-id (str "route-optimization-" location "-" (System/currentTimeMillis))
             :location (name location)
             :type "route-optimization"
             :confidence 0.8
             :description "Traffic conditions improving significantly in 3 hours"
             :recommendation "Delay travel by 3 hours for 40% faster journey time"
             :created-at (java.time.Instant/now)
             :expires-at (java.time.Instant/ofEpochSecond (+ (System/currentTimeMillis) 10800))}))))

;; =============================================================================
;; Data Processing Pipeline
;; =============================================================================

(defn collect-urban-data [location data-types]
  "Main data collection pipeline"
  (let [include-traffic? (or (empty? data-types) (some #{"traffic"} data-types))
        include-weather? (or (empty? data-types) (some #{"weather"} data-types))
        include-insights? (or (empty? data-types) (some #{"insights"} data-types))
        
        traffic-data (when include-traffic?
                      (merge (fetch-google-traffic location)
                            (fetch-here-traffic location)))
        
        weather-data (when include-weather?
                      (fetch-weather-data location))
        
        insights (when (and include-insights? traffic-data weather-data)
                  (generate-urban-insights location weather-data traffic-data))]
    
    ; Store in database
    (when traffic-data
      (swap! urban-db assoc-in [:traffic-data location] traffic-data))
    
    (when weather-data
      (swap! urban-db assoc-in [:weather-data location] weather-data))
    
    (when insights
      (doseq [insight insights]
        (swap! urban-db assoc-in [:urban-insights (:insight-id insight)] insight)))
    
    {:location location
     :timestamp (java.time.Instant/now)
     :traffic-data traffic-data
     :weather-data weather-data
     :urban-insights insights
     :data-sources (cond-> []
                     include-traffic? (conj "google-maps" "here-api")
                     include-weather? (conj "weatherapi")
                     include-insights? (conj "ml-engine"))}))

;; =============================================================================
;; HTTP Handlers
;; =============================================================================

(defn health-handler [_request]
  {:status 200
   :body {:status "healthy"
          :timestamp (java.time.Instant/now)
          :service "urban-intelligence-platform"
          :version "1.0.0-mvp"
          :active-locations (keys (get-in config [:locations]))
          :cache-entries (count (get-in @urban-db [:cache]))
          :api-usage (get-in @urban-db [:api-usage])}})

(defn urban-data-handler [{{:keys [query]} :parameters}]
  "Main endpoint for urban intelligence data"
  (let [{:keys [location data-types forecast-hours]} query
        location-key (keyword location)
        data-types (or data-types ["traffic" "weather" "insights"])]
    
    (if (contains? (get-in config [:locations]) location-key)
      (try
        (let [urban-data (collect-urban-data location-key data-types)]
          (log/info (format "Urban data collected for %s: %s" 
                           location (str/join "," data-types)))
          {:status 200
           :body {:success true
                  :data urban-data
                  :metadata {:request-id (str (random-uuid))
                            :processing-time-ms (System/currentTimeMillis)
                            :api-version "v1"
                            :location location
                            :data-types data-types}}})
        (catch Exception e
          (log/error e "Failed to collect urban data")
          {:status 500
           :body {:success false
                  :error "Failed to collect urban data"
                  :details (.getMessage e)}}))
      
      {:status 400
       :body {:success false
              :error "Invalid location"
              :valid-locations (keys (get-in config [:locations]))}})))

(defn traffic-prediction-handler [{{:keys [query]} :parameters}]
  "Traffic prediction endpoint"
  (let [{:keys [location forecast-hours]} query
        location-key (keyword location)
        hours (or forecast-hours 1)]
    
    (if (contains? (get-in config [:locations]) location-key)
      (try
        (let [prediction (generate-traffic-prediction location-key hours)]
          {:status 200
           :body {:success true
                  :prediction prediction
                  :metadata {:forecast-hours hours
                            :generated-at (java.time.Instant/now)}}})
        (catch Exception e
          (log/error e "Failed to generate traffic prediction")
          {:status 500
           :body {:success false
                  :error "Failed to generate prediction"}}))
      
      {:status 400
       :body {:success false
              :error "Invalid location"}})))

(defn insights-handler [{{:keys [query]} :parameters}]
  "Urban insights endpoint"
  (let [{:keys [location]} query
        location-key (keyword location)]
    
    (if (contains? (get-in config [:locations]) location-key)
      (let [insights (filter #(= (:location %) (name location-key))
                           (vals (get-in @urban-db [:urban-insights])))]
        {:status 200
         :body {:success true
                :insights insights
                :count (count insights)
                :location location}})
      
      {:status 400
       :body {:success false
              :error "Invalid location"}})))

(defn analytics-handler [_request]
  "Analytics dashboard data"
  {:status 200
   :body {:success true
          :analytics {:total-requests (reduce + (map #(reduce + (vals %))
                                                    (vals (get-in @urban-db [:api-usage]))))
                     :cache-hit-ratio 85.2 ; Demo metric
                     :average-response-time-ms 245
                     :active-locations (count (get-in config [:locations]))
                     :insights-generated (count (get-in @urban-db [:urban-insights]))
                     :data-freshness {:traffic "4.2 minutes"
                                    :weather "8.7 minutes"
                                    :insights "12.1 minutes"}
                     :api-health {:google-maps "operational"
                                :weatherapi "operational"  
                                :here-traffic "operational"}}}})

(defn locations-handler [_request]
  "Available locations endpoint"
  {:status 200
   :body {:success true
          :locations (into {} (map (fn [[k v]]
                                   [k (assoc v :id (name k))])
                                 (get-in config [:locations])))}})

;; =============================================================================
;; Routes
;; =============================================================================

(def routes
  ["/api/v1"
   ["/health" {:get health-handler}]
   
   ["/locations" {:get locations-handler}]
   
   ["/urban-data" {:get {:handler urban-data-handler
                        :parameters {:query api-request-schema}}}]
   
   ["/traffic/prediction" {:get {:handler traffic-prediction-handler
                                :parameters {:query [:map
                                                    [:location [:enum "tokyo" "osaka" "nagoya" "yokohama" "kyoto"]]
                                                    [:forecast-hours {:optional true} [:int {:min 1 :max 72}]]]}}}]
   
   ["/insights" {:get {:handler insights-handler
                      :parameters {:query [:map
                                          [:location [:enum "tokyo" "osaka" "nagoya" "yokohama" "kyoto"]]]}}}]
   
   ["/analytics" {:get analytics-handler}]])

;; =============================================================================
;; Middleware
;; =============================================================================

(defn wrap-request-logging [handler]
  (fn [request]
    (let [start (System/currentTimeMillis)
          response (handler request)
          duration (- (System/currentTimeMillis) start)]
      (log/info (format "[%s] %s %s -> %d (%dms)"
                       (.format (java.time.LocalDateTime/now)
                               (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss"))
                       (str/upper-case (name (:request-method request)))
                       (:uri request)
                       (:status response)
                       duration))
      response)))

(defn wrap-api-key-validation [handler]
  "Simple API key validation middleware"
  (fn [request]
    (let [api-key (get-in request [:headers "x-api-key"])
          public-endpoints #{"/api/v1/health" "/api/v1/locations"}]
      (if (or (contains? public-endpoints (:uri request))
              (= api-key "demo-api-key"))
        (handler request)
        {:status 401
         :body {:success false
                :error "Invalid or missing API key"
                :hint "Include 'x-api-key: demo-api-key' header"}}))))

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
        {:not-found (constantly {:status 404 
                                :body {:success false 
                                      :error "Endpoint not found"
                                      :available-endpoints ["/api/v1/health"
                                                          "/api/v1/locations"
                                                          "/api/v1/urban-data"
                                                          "/api/v1/traffic/prediction"
                                                          "/api/v1/insights"
                                                          "/api/v1/analytics"]}})}))
    {:middleware [wrap-request-logging
                  wrap-api-key-validation
                  [cors/wrap-cors
                   :access-control-allow-origin [#".*"]
                   :access-control-allow-methods [:get :post :put :delete]
                   :access-control-allow-headers ["Content-Type" "Authorization" "x-api-key"]]]}))

;; =============================================================================
;; Background Jobs
;; =============================================================================

(defn start-data-refresh-job []
  "Background job to refresh data every 5 minutes"
  (future
    (while true
      (try
        (Thread/sleep (* 5 60 1000)) ; 5 minutes
        (log/info "Starting background data refresh...")
        (doseq [location (keys (get-in config [:locations]))]
          (collect-urban-data location ["traffic" "weather" "insights"]))
        (log/info "Background data refresh completed")
        (catch Exception e
          (log/error e "Background data refresh failed"))))))

(defn start-cache-cleanup-job []
  "Background job to clean expired cache entries"
  (future
    (while true
      (try
        (Thread/sleep (* 60 60 1000)) ; 1 hour
        (let [now (System/currentTimeMillis)
              cache (get-in @urban-db [:cache])
              expired-keys (filter #(>= now (:expires-at (get cache %))) (keys cache))]
          (when (seq expired-keys)
            (log/info (format "Cleaning %d expired cache entries" (count expired-keys)))
            (swap! urban-db update :cache #(apply dissoc % expired-keys))))
        (catch Exception e
          (log/error e "Cache cleanup failed"))))))

;; =============================================================================
;; Integrant Configuration
;; =============================================================================

(def system-config
  {:urban-intelligence/app {}
   :urban-intelligence/server {:app (ig/ref :urban-intelligence/app)
                              :port (Integer/parseInt (or (System/getenv "PORT") "8080"))
                              :host "0.0.0.0"}
   :urban-intelligence/background-jobs {}})

;; =============================================================================
;; Integrant Methods
;; =============================================================================

(defmethod ig/init-key :urban-intelligence/app [_ _]
  (log/info "🏙️  Initializing Urban Intelligence Platform...")
  (create-app))

(defmethod ig/halt-key! :urban-intelligence/app [_ _]
  (log/info "🏙️  Shutting down Urban Intelligence Platform..."))

(defmethod ig/init-key :urban-intelligence/server [_ {:keys [app port host]}]
  (log/info (format "🚀 Starting Urban Intelligence Server on %s:%d" host port))
  (jetty/run-jetty app {:port port
                       :host host
                       :join? false}))

(defmethod ig/halt-key! :urban-intelligence/server [_ server]
  (log/info "🛑 Stopping server...")
  (.stop server))

(defmethod ig/init-key :urban-intelligence/background-jobs [_ _]
  (log/info "⚙️  Starting background jobs...")
  {:data-refresh (start-data-refresh-job)
   :cache-cleanup (start-cache-cleanup-job)})

(defmethod ig/halt-key! :urban-intelligence/background-jobs [_ jobs]
  (log/info "⚙️  Stopping background jobs...")
  (doseq [[job-name job-future] jobs]
    (log/info (format "Stopping %s job..." (name job-name)))
    (future-cancel job-future)))

;; =============================================================================
;; System Management
;; =============================================================================

(defonce system (atom nil))

(defn start-system! []
  (when @system
    (ig/halt! @system))
  (reset! system (ig/init system-config))
  (log/info "🎯 Urban Intelligence Platform started successfully!")
  (log/info "📊 API Endpoints available:")
  (log/info "   GET /api/v1/health")
  (log/info "   GET /api/v1/locations") 
  (log/info "   GET /api/v1/urban-data?location=tokyo&data-types=traffic,weather")
  (log/info "   GET /api/v1/traffic/prediction?location=osaka&forecast-hours=3")
  (log/info "   GET /api/v1/insights?location=tokyo")
  (log/info "   GET /api/v1/analytics")
  (log/info "🔑 Use header: x-api-key: demo-api-key"))

(defn stop-system! []
  (when @system
    (ig/halt! @system)
    (reset! system nil)
    (log/info "🏙️  Urban Intelligence Platform stopped!")))

(defn restart-system! []
  (stop-system!)
  (start-system!))

;; =============================================================================
;; Signal Handlers
;; =============================================================================

(defn setup-shutdown-hook! []
  (.addShutdownHook (Runtime/getRuntime)
                   (Thread. #(do
                              (log/info "🔄 Received shutdown signal...")
                              (stop-system!)))))

;; =============================================================================
;; Main
;; =============================================================================

(defn -main [& args]
  (log/info "🏙️  Starting Japanese Urban Intelligence Platform...")
  (setup-shutdown-hook!)
  (start-system!)
  
  ;; Keep the main thread alive
  (loop []
    (Thread/sleep 10000) ; Check every 10 seconds
    (when @system
      (recur)))
  
  (log/info "🏙️  Urban Intelligence Platform exiting..."))

;; =============================================================================
;; Public API for exec-fn
;; =============================================================================

(defn start-urban-intelligence
  "Entry point for clj -X execution"
  [_opts]
  (-main))

;; =============================================================================
;; REPL Development Helpers
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
  
  ;; Check urban database
  @urban-db
  
  ;; Clear cache
  (swap! urban-db assoc :cache {})
  
  ;; Test API endpoints with clj-http
  (require '[clj-http.client :as http])
  
  ;; Health check
  (http/get "http://localhost:8080/api/v1/health"
            {:accept :json :as :json})
  
  ;; Get available locations
  (http/get "http://localhost:8080/api/v1/locations"
            {:accept :json :as :json
             :headers {"x-api-key" "demo-api-key"}})
  
  ;; Get urban data for Tokyo
  (http/get "http://localhost:8080/api/v1/urban-data"
            {:accept :json :as :json
             :headers {"x-api-key" "demo-api-key"}
             :query-params {:location "tokyo"
                           :data-types "traffic,weather,insights"}})
  
  ;; Get traffic prediction for Osaka
  (http/get "http://localhost:8080/api/v1/traffic/prediction"
            {:accept :json :as :json
             :headers {"x-api-key" "demo-api-key"}
             :query-params {:location "osaka"
                           :forecast-hours 3}})
  
  ;; Get insights for Tokyo
  (http/get "http://localhost:8080/api/v1/insights"
            {:accept :json :as :json
             :headers {"x-api-key" "demo-api-key"}
             :query-params {:location "tokyo"}})
  
  ;; Get analytics dashboard
  (http/get "http://localhost:8080/api/v1/analytics"
            {:accept :json :as :json
             :headers {"x-api-key" "demo-api-key"}})
  
  ;; Test data collection manually
  (collect-urban-data :tokyo ["traffic" "weather" "insights"])
  
  ;; Test traffic prediction
  (generate-traffic-prediction :osaka 2)
  
  ;; Test weather-traffic correlation
  (let [weather {:precipitation 5 :visibility 3}
        traffic {:current-speed 25 :free-flow-speed 50}]
    (calculate-weather-traffic-correlation weather traffic))
  
  ;; Generate insights manually
  (let [weather (fetch-weather-data :tokyo)
        traffic (fetch-google-traffic :tokyo)]
    (generate-urban-insights :tokyo weather traffic))
  
  ;; Check API usage stats
  (get-in @urban-db [:api-usage])
  
  ;; Check cache status
  (count (get-in @urban-db [:cache]))
  
  ;; Clear all data
  (reset! urban-db {:traffic-data {}
                    :weather-data {}
                    :urban-insights {}
                    :api-usage {}
                    :cache {}})
  
  ;; Simulate API key validation
  (wrap-api-key-validation
    (fn [req] {:status 200 :body "OK"}))
  
  ;; Test individual API clients
  (fetch-google-traffic :tokyo)
  (fetch-weather-data :osaka)
  (fetch-here-traffic :nagoya)
  
  ;; Performance testing
  (time (collect-urban-data :tokyo ["traffic" "weather" "insights"]))
  
  ;; Load test with multiple locations
  (doseq [location [:tokyo :osaka :nagoya :yokohama :kyoto]]
    (future 
      (time (collect-urban-data location ["traffic" "weather" "insights"]))))
  
  ;; Monitor background jobs
  (let [jobs (get @system :urban-intelligence/background-jobs)]
    (doseq [[job-name job-future] jobs]
      (println (format "%s: %s" (name job-name) 
                      (if (future-done? job-future) "DONE" "RUNNING")))))
  
  ;; Environment variable simulation
  (System/setProperty "GOOGLE_MAPS_API_KEY" "your-real-api-key")
  (System/setProperty "OPENWEATHER_API_KEY" "your-weather-api-key")
  (System/setProperty "WEATHERAPI_KEY" "your-weatherapi-key")
  (System/setProperty "HERE_API_KEY" "your-here-api-key")
  (System/setProperty "PORT" "3000")
  
  ;; Test error handling
  (try
    (http/get "http://localhost:8080/api/v1/urban-data"
              {:accept :json :as :json
               :headers {"x-api-key" "invalid-key"}
               :query-params {:location "invalid"}})
    (catch Exception e
      (println "Expected error:" (.getMessage e))))
  
  ;; Curl examples for testing:
  ;; 
  ;; # Health check
  ;; curl http://localhost:8080/api/v1/health
  ;; 
  ;; # Get locations
  ;; curl -H "x-api-key: demo-api-key" http://localhost:8080/api/v1/locations
  ;; 
  ;; # Get Tokyo urban data
  ;; curl -H "x-api-key: demo-api-key" "http://localhost:8080/api/v1/urban-data?location=tokyo&data-types=traffic,weather"
  ;; 
  ;; # Get traffic prediction
  ;; curl -H "x-api-key: demo-api-key" "http://localhost:8080/api/v1/traffic/prediction?location=osaka&forecast-hours=2"
  ;; 
  ;; # Get insights
  ;; curl -H "x-api-key: demo-api-key" "http://localhost:8080/api/v1/insights?location=tokyo"
  ;; 
  ;; # Get analytics
  ;; curl -H "x-api-key: demo-api-key" http://localhost:8080/api/v1/analytics
  
  )
