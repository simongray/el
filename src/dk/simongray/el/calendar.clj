(ns dk.simongray.el.calendar
  (:require [clojure.data.json :as json]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.content-negotiation :as conneg]
            [ring.util.codec :as codec]                     ; NOTE: transitive
            [clj-http.client :as client]
            [tick.core :as t]
            [clj-icalendar.core :as ical]
            [rum.core :as rum]
            [tongue.core :as tongue]
            [dk.simongray.el.prices :as el])
  (:gen-class))

(defonce server (atom nil))

(defn- round
  [n]
  (let [factor (Math/pow 10 2)]
    (/ (Math/round (float (* n factor))) factor)))

(def translate
  (tongue/build-translate
    {:en {:tongue/format-number (comp (tongue/number-formatter {:group   ","
                                                                :decimal "."})
                                      round)
          :tr/evt-low-title     "Average: ~{1} {2}/kWh"
          :tr/evt-min-title     "Lowest: {1} {2}/kWh"
          :tr/evt-max-title     "Highest: {1} {2}/kWh"
          :tr/evt-low-desc      "The local minimum spot price occurs at {1} (price: {2} {3}/kWh)."
          :tr/evt-min-desc      "The daily minimum spot price is {1} {2}/kWh."
          :tr/evt-max-desc      "The daily maximum spot price is {1} {2}/kWh."
          :tr/title             "Danish electricity calendar"
          :tr/subscribe         "Subscribe"
          :tr/google-note       "I use Google Calendar"
          :tr/max-price         "Price ceiling"
          :tr/max-price-msg     "DKK / kWh"
          :tr/region            "Area"
          :tr/dk                "Unsure"
          :tr/dk1               "West Denmark"
          :tr/dk2               "East Denmark"
          :tr/p2                (str
                                  "Many danish households pay a fluctuating price for electricity. "
                                  "In fact, the spot price—i.e. the market price—of electricity changes every hour. "
                                  "Usually the price will be around 0 to 3 DKK per kWh before taxes.")
          :tr/p3                (str
                                  "If you have a fluctuating electricity price, you might as well plan usage spikes"
                                  "—e.g. use of a dishwasher, tumble dryer, or washing machine—"
                                  "according to when the spot price is low.")
          :tr/p1                (str
                                  "Here you can subscribe to an automatically updating calendar showing when "
                                  "electricity in Denmark will be cheap during the coming 24 to 48 hours.")
          :tr/note              "(set the update schedule to 1 hour or similar)"}
     :da {:tongue/format-number (comp (tongue/number-formatter {:group   "."
                                                                :decimal ","})
                                      round)
          :tr/evt-low-title     "Gennemsnit: ~{1} {2}/kWh"
          :tr/evt-min-title     "Lavest: {1} {2}/kWh"
          :tr/evt-max-title     "Højest: {1} {2}/kWh"
          :tr/evt-low-desc      "Det lokale lavpunkt for spotprisen finder sted kl. {1} (pris: {2} {3}/kWh)."
          :tr/evt-min-desc      "Dagens minimumsspotpris er {1} {2}/kWh."
          :tr/evt-max-desc      "Dagens maksimumsspotpris er {1} {2}/kWh."
          :tr/title             "Dansk el-kalender"
          :tr/subscribe         "Abonnér"
          :tr/google-note       "Jeg bruger Google Kalender"
          :tr/max-price         "Prisloft"
          :tr/max-price-msg     "kr. / kWh"
          :tr/region            "Område"
          :tr/dk                "Ved ikke"
          :tr/dk1               "Vestdanmark"
          :tr/dk2               "Østdanmark"
          :tr/p2                (str
                                  "Mange danske husstande har en variabel elpris. "
                                  "Faktisk ændrer spotprisen—dvs. markedsprisen—på el sig hver eneste time. "
                                  "Som regel ligger prisen et sted mellem 0 og 3 kr. per kWh før skat. ")
          :tr/p3                (str
                                  "Hvis du har en variabel elpris, kan du med fordel planlægge større strømforbrug"
                                  "—f.eks. brug af vaskemaskine, tørretumbler eller opvaskemaskine—"
                                  "efter hvornår spotprisen er lav.")
          :tr/p1                (str
                                  "Her kan du abonnere på en automatisk opdaterende kalender, der viser hvornår "
                                  "elektriciteten i Danmark vil være billig i de kommende 24 til 48 timer.")
          :tr/note              "(sæt opdateringsraten til 1 time eller lignende)"}}))

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
                      [:input {:id   "google"
                               :name "google"
                               :type "checkbox"}]
                      " " [:label {:for "google"} (tr :tr/google-note)]]
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

(def uid-datetime-fmt
  "yyyy-MM-dd-HH")

(defn- uid
  "Generate a unique ID for an event of `event-type` with `start` and `end`."
  [event-type start end]
  (str (name event-type) "--"
       (t/format uid-datetime-fmt start) "--"
       (t/format uid-datetime-fmt end)))

(defn- add-extrema!
  [{:keys [cal timestamp title description region]}]
  (let [end (t/>> timestamp (t/of-hours 1))]
    (ical/add-event! cal (ical/create-event
                           (t/inst timestamp)
                           (t/inst end)
                           title
                           :unique-id (uid :extrema timestamp end)
                           :description description
                           :location region
                           :organizer "spot-prices"))))

(defn ics-body
  "Icalendar-formatted body for :groups, :minima, and :maxima in `opts`."
  [{:keys [region language currency max-price
           groups minima maxima] :as opts}]
  (let [cal     (ical/create-cal "el-priser" "el-priser" "V0.1" "EN")
        extrema (set (map vector (concat minima maxima)))
        tr      (partial translate (keyword language))]

    ;; Add low-price events.
    (doseq [{:keys [from to mean minimum]} (->> (remove extrema groups)
                                                (map price-summary))
            :let [{:keys [timestamp price-kwh]} minimum
                  title       (tr :tr/evt-low-title mean currency)
                  hh-mm       (t/format time-fmt timestamp)
                  description (tr :tr/evt-low-desc hh-mm price-kwh currency)
                  start       (:timestamp from)
                  end         (t/>> (:timestamp to) (t/of-hours 1))]]
      (ical/add-event! cal (ical/create-event
                             (t/inst start)
                             (t/inst end)
                             title
                             :unique-id (uid :low start end)
                             :description description
                             :location (:region from)
                             :organizer "spot-prices")))

    ;; Add daily minimum price events.
    (doseq [{:keys [timestamp price-kwh region]} minima
            :let [title       (tr :tr/evt-min-title price-kwh currency)
                  description (tr :tr/evt-min-desc price-kwh currency)]]
      (add-extrema! {:cal         cal
                     :timestamp   timestamp
                     :title       title
                     :description description
                     :region      region}))

    ;; Add daily maximum price events.
    (doseq [{:keys [timestamp price-kwh region]} maxima
            :let [title       (tr :tr/evt-max-title price-kwh currency)
                  description (tr :tr/evt-max-desc price-kwh currency)]]
      (add-extrema! {:cal         cal
                     :timestamp   timestamp
                     :title       title
                     :description description
                     :region      region}))
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
        prices       (el/normalize currency (el/current-prices params))
        ->body       (body-fn content-type)]
    {:status  200
     :headers {"Content-Type"     content-type
               "Content-Language" language}
     :body    (->body {:region    region
                       :language  language
                       :currency  currency
                       :max-price max-price
                       :groups    (-> (el/prices-below n prices)
                                      (el/group-adjacent))
                       :minima    (el/daily-minima prices)
                       :maxima    (el/daily-maxima prices)})}))

(defn subscribe-handler
  "Handler which redirects an HTTPS subcribe request to the webcal protocol.
  Optionally redirects towards Google Calendar with the webcal link as a param.

  The main purpose is to circumvent Chrome's 'not fully secure' message which
  occurs if a form submits to a non-HTTPS URL, e.g. webcal://."
  [{:keys [query-params headers] :as request}]
  (let [google?      (:google query-params)
        gcal         "https://calendar.google.com/calendar/u/0/r?cid="
        webcal       (str "webcal://" (get headers "host") "/calendar")
        params       (codec/form-encode (dissoc query-params :google))
        calendar-url (str webcal "?" params)]
    {:status  303
     :headers {"Location" (if google?
                            (str gcal (codec/form-encode calendar-url))
                            calendar-url)}}))

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

(defn -main
  [& args]
  (start-server))

(comment
  (restart-dev-server)
  (stop-dev-server)

  ;; Test the local endpoint.
  (client/get "http://localhost:9876//calendar" {:query-params
                                                 {:language  "da"
                                                  :currency  "DKK"
                                                  :max-price "1.00"
                                                  :region    "DK2"}})
  #_.)
