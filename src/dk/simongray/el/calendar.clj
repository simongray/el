(ns dk.simongray.el.calendar
  (:require [clojure.data.json :as json]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.content-negotiation :as conneg]
            [ring.util.codec :as codec]                     ; NOTE: transitive
            [clj-http.client :as client]
            [tick.core :as tick]
            [clj-icalendar.core :as ical]
            [rum.core :as rum]
            [tongue.core :as tongue]
            [dk.simongray.el.prices :as el]))

(defonce server (atom nil))

(def dicts
  {:en {:tr/title         "Danish electricity calendar"
        :tr/subscribe     "Subscribe"
        :tr/max-price     "Price ceiling"
        :tr/max-price-msg "DKK / kWh"
        :tr/region        "Area"
        :tr/dk            "Unsure"
        :tr/dk1           "West Denmark"
        :tr/dk2           "East Denmark"
        :tr/p1            (str
                            "Many danish households pay a fluctuating price for electricity. "
                            "In fact, the spot price—i.e. the market price—of electricity changes every hour. "
                            "Usually the price will be around 0 to 3 DKK per kWh before taxes.")
        :tr/p2            (str
                            "If you have a fluctuating electricity price, you might as well plan usage spikes"
                            "—e.g. use of a dishwasher, tumble dryer, or washing machine—"
                            "according to when the spot price is low.")
        :tr/p3            (str
                            "Here you can subscribe to an automatically updating calendar showing when "
                            "electricity in Denmark will be cheap during the coming 24 to 48 hours.")
        :tr/note          "(set the update schedule to 1 hour or similar)"}
   :da {:tr/title         "Dansk el-kalender"
        :tr/subscribe     "Abonnér"
        :tr/max-price     "Prisloft"
        :tr/max-price-msg "kr. / kWh"
        :tr/region        "Område"
        :tr/dk            "Ved ikke"
        :tr/dk1           "Vestdanmark"
        :tr/dk2           "Østdanmark"
        :tr/p1            (str
                            "Mange danske husstande har en variabel elpris. "
                            "Faktisk ændrer spotprisen—dvs. markedsprisen—på el sig hver eneste time. "
                            "Som regel ligger prisen et sted mellem 0 og 3 kr. per kWh før skat. ")
        :tr/p2            (str
                            "Hvis du har en variabel elpris, kan du med fordel planlægge større strømforbrug"
                            "—f.eks. brug af vaskemaskine, tørretumbler eller opvaskemaskine—"
                            "efter hvornår spotprisen er lav.")
        :tr/p3            (str
                            "Her kan du abonnere på en automatisk opdaterende kalender, der viser hvornår "
                            "elektriciteten i Danmark vil være billig i de kommende 24 til 48 timer.")
        :tr/note          "(sæt opdateringsraten til 1 time eller lignende)"}})

(def translate
  (tongue/build-translate dicts))

(defn ->language-negotiation-ic
  "Make a language negotiation interceptor from a coll of `supported-languages`.

  The interceptor reuses Pedestal's content-negotiation logic, but unlike the
  included content negotiation interceptor this one does not create a 406
  response if no match is found."
  [supported-languages]
  (let [match-fn   (conneg/best-match-fn supported-languages)
        lang-paths [[:request :headers "accept-language"]
                    [:request :headers :accept-language]]]
    {:name  ::negotiate-language
     :enter (fn [ctx]
              (if-let [accept-param (loop [[path & paths] lang-paths]
                                      (if-let [param (get-in ctx path)]
                                        param
                                        (when (not-empty paths)
                                          (recur paths))))]
                (if-let [language (->> (conneg/parse-accept-* accept-param)
                                       (conneg/best-match match-fn))]
                  (assoc-in ctx [:request :accept-language] language)
                  ctx)
                ctx))}))

(def language-negotiation-ic
  (->language-negotiation-ic ["en" "da"]))

(defn root-handler
  "Handler serving a basic HTML landing page."
  [request]
  (let [language (get-in request [:accept-language :field] "en")
        tr       (partial translate (keyword language))]
    {:status  200
     :headers {"Content-Type"     "text/html"
               "Content-Language" language}
     :body    (rum/render-static-markup
                [:html
                 [:head
                  [:title (tr :tr/title)]
                  [:meta {:name    "viewport"
                          :content "width=device-width, initial-scale=1.0"}]
                  [:link {:rel "icon" :href "/favicon.svg"}]
                  [:link {:rel "stylesheet" :href "/pure-min.css"}]
                  [:link {:rel "stylesheet" :href "/main.css"}]
                  [:meta {:charset "UTF-8"}]]
                 [:body {:lang language}
                  [:main
                   [:h1
                    [:img {:src "favicon.svg" :alt ""}]
                    (tr :tr/title)]
                   [:p (tr :tr/p1)]
                   [:p (tr :tr/p2)]
                   [:p (tr :tr/p3)]
                   [:address
                    "~ Simon Gray ("
                    [:a {:href "https://github.com/simongray/el"}
                     "Github"]
                    ")"]
                   [:form {:class  "pure-form pure-form-aligned"
                           :action "/subscribe"}
                    [:input {:type  "hidden"
                             :name  "language"
                             :value language}]
                    [:input {:type  "hidden"
                             :name  "currency"
                             :value "DKK"}]
                    [:fieldset
                     [:div.pure-control-group
                      [:label {:for "max-price"}
                       (tr :tr/max-price)]
                      [:input {:id            "max-price"
                               :name          "max-price"
                               :type          "number"
                               :step          "0.01"
                               :default-value "1.00"}]
                      [:span.pure-form-message-inline (tr :tr/max-price-msg)]]
                     [:div.pure-control-group
                      [:label {:for "region"}
                       (tr :tr/region)]
                      [:select {:id            "region"
                                :name          "region"
                                :default-value "DK2"}
                       [:option {:value "DK"} (tr :tr/dk)]
                       [:option {:value "DK1"} (tr :tr/dk1)]
                       [:option {:value "DK2"} (tr :tr/dk2)]]]
                     [:div.pure-controls
                      [:input {:value (tr :tr/subscribe)
                               :class "pure-button pure-button-primary"
                               :type  "submit"}]
                      [:span.pure-form-message-inline (tr :tr/note)]]]]]]])}))

(defn price-summary
  [price-groups]
  {:from    (first price-groups)
   :to      (last price-groups)
   :mean    (/ (apply + (map :price-kwh price-groups))
               (count price-groups))
   :minimum (first (sort-by :price-kwh price-groups))})

(def time-fmt
  "HH:mm")

(defn- add-min-max
  [cal timestamp title description region]
  (ical/add-event! cal (ical/create-event
                         (tick/inst timestamp)
                         (tick/inst (tick/>> timestamp
                                             (tick/of-hours 1)))
                         title
                         :unique-id (str (random-uuid))
                         :description description
                         :location region
                         :organizer "spot-prices")))

(defn- price-str
  [price-kwh currency]
  (str (format "%.2f" price-kwh) " "
       currency "/kWh"))

(defn ics-body
  "Icalendar-formatted body for :groups, :minima, and :maxima in `opts`."
  [{:keys [groups minima maxima] :as opts}]
  (let [cal      (ical/create-cal "el-priser" "el-priser" "V0.1" "EN")
        currency (:currency (first minima))
        extrema  (set (map vector (concat minima maxima)))]
    (doseq [{:keys [from to mean minimum]} (->> (remove extrema groups)
                                                (map price-summary))
            :let [{:keys [timestamp price-kwh]} minimum
                  title       (str "Average: ~" (price-str mean currency))
                  description (str "The local minimum spot price occurs at "
                                   (tick/format time-fmt timestamp) " (price: "
                                   (price-str price-kwh currency) ").")]]
      (ical/add-event! cal (ical/create-event
                             (tick/inst (:timestamp from))
                             (tick/inst (tick/>> (:timestamp to)
                                                 (tick/of-hours 1)))
                             title
                             :unique-id (str (random-uuid))
                             :description description
                             :location (:region from)
                             :organizer "spot-prices")))
    (doseq [{:keys [timestamp price-kwh region]} minima
            :let [title       (str "Lowest: " (price-str price-kwh currency))
                  description (str "The daily minimum spot price is "
                                   (price-str price-kwh currency) ".")]]
      (add-min-max cal timestamp title description region))
    (doseq [{:keys [timestamp price-kwh region]} maxima
            :let [title       (str "Highest: " (price-str price-kwh currency))
                  description (str "The daily maximum spot price is "
                                   (str (format "%.2f" price-kwh) " "
                                        currency "/KWh" "."))]]
      (add-min-max cal timestamp title description region))
    (ical/output-calendar cal)))

(def content-type-body-kvs
  [["text/calendar" ics-body]
   ["application/edn" pr-str]])

(def body-fn
  (into {} content-type-body-kvs))

(defn calendar-handler
  "Handler serving the iCalendar data."
  [{:keys [query-params] :as request}]
  (let [{:keys [region language currency max-price]
         :or   {region    "DK2"
                language  "en"
                currency  "DKK"
                max-price "1"}} query-params
        content-type (get-in request [:accept :field] "text/calendar")
        params       (assoc el/default-params
                       :filters (json/write-str {"PriceArea" region}))
        n            (parse-double max-price)
        prices       (el/normalize currency (el/current-prices params))]
    {:status  200
     :headers {"Content-Type"     content-type
               "Content-Language" language}
     :body    ((body-fn content-type) {:groups (-> (el/prices-below n prices)
                                                   (el/group-adjacent))
                                       :minima (el/daily-minima prices)
                                       :maxima (el/daily-maxima prices)})}))

(defn subscribe-handler
  "Handler which redirects an HTTPS subcribe request to the webcal protocol.

  The main purpose is to circumvent Chrome's 'not fully secure' message which
  occurs if a form submits to a non-HTTPS URL, e.g. webcal://."
  [{:keys [query-params headers] :as request}]
  (let [webcal (str "webcal://" (get headers "host") "/calendar")
        params (codec/form-encode query-params)]
    {:status  303
     :headers {"Location" (str webcal "?" params)}}))

(def content-negotiation-ic
  (conneg/negotiate-content (map first content-type-body-kvs)))

(defn routes
  []
  (route/expand-routes
    #{["/"
       :get [language-negotiation-ic root-handler]
       :route-name ::root]
      ["/subscribe"
       :get [subscribe-handler]
       :route-name ::subscribe]
      ["/calendar"
       :get [content-negotiation-ic calendar-handler]
       :route-name ::calendar]}))

;; TODO: take a look at this
(def service-map
  (let [csp {:default-src "'none'"
             :script-src  "'self' 'unsafe-inline'"
             :connect-src "'self'"
             :img-src     "'self'"
             :font-src    "'self'"
             :style-src   "'self' 'unsafe-inline'"
             :base-uri    "'self'"}]
    (cond-> {::http/routes         #((deref #'routes))
             ::http/type           :jetty
             ::http/host           "127.0.0.1"
             ::http/port           9876
             ::http/resource-path  "/public"
             ::http/secure-headers {:content-security-policy-settings csp}})))

(defn start-server [& args]
  (http/start (http/create-server service-map)))

(defn start-dev-server []
  (->> (assoc service-map ::http/join? false)
       (http/create-server)
       (http/start)
       (reset! server)))

(defn stop-dev-server []
  (http/stop @server))

(defn restart-dev-server []
  (when @server
    (stop-dev-server))
  (start-dev-server))

(comment
  (restart-dev-server)

  ;; Test the local endpoint.
  (client/get "http://localhost:9876//calendar" {:query-params
                                                 {:language  "da"
                                                  :currency  "DKK"
                                                  :max-price "1.00"
                                                  :region    "DK2"}})
  #_.)
