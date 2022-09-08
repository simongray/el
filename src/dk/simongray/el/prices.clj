(ns dk.simongray.el.prices
  "Uses the public electricity spot price API from Energi Data Service."
  (:require [clojure.data.json :as json]
            [clojure.data.xml :as xml]
            [clj-http.client :as client]
            [again.core :as again]
            [io.pedestal.log :as log]
            [tick.core :as t]))

(defonce exchange-rates
  (atom nil))

(defonce prices
  (atom {}))

;; https://rymndhng.github.io/2020/04/15/production-considerations-for-clj-http
(def request-opts
  {:throw-exceptions           false
   :retry-handler              nil
   :socket-timeout             10000
   :connection-timeout         10000
   :connection-request-timeout 10000})

(def fmt
  (t/formatter :iso-local-date-time :dk))

(def exchange-rates-url
  "https://www.nationalbanken.dk/_vti_bin/DN/DataService.svc/CurrencyRatesXML?lang=en")

(def energy-api-url
  "https://api.energidataservice.dk/dataset/Elspotprices")

(def default-params
  {:sort   "HourDK DESC"
   :filter (json/write-str {"PriceArea" "DK2"})})

(defn- hours-old
  [state-val n]
  (if-let [ts (-> state-val meta :timestamp)]
    (t/> (t/now) (t/>> ts (t/of-hours n)))
    true))

(defn- bad-api-result?
  "Make sure we are not receiving error page content in `response`."
  [{:keys [status headers] :as response}]
  (or (not= 200 status)
      (= "text/html" (get headers "Content-type"))))

(def mappify-rates-xf
  (comp
    (map :attrs)
    (map (juxt :code #(-> % (dissoc :code) (update :rate parse-double))))))

(defn fetch-exchange-rates
  "Fetch currency exchange rates from Nationalbanken."
  []
  (let [result (client/get exchange-rates-url request-opts)]
    (if (bad-api-result? result)
      (throw (ex-info "Bad API result" result))
      (let [xml     (xml/parse-str (:body result))
            content (-> xml :content first :content)]
        (into {} mappify-rates-xf content)))))

(defn api-swap!
  "Reset `atom` with API data from `f` & `args` using a basic retry strategy."
  [atom f & args]
  (try
    (again/with-retries
      [100 1000 3000]
      (apply swap! atom f args))
    (catch Exception e
      (log/error :api-fail e))))

(defn timestamped
  "Return a function which timestamps the result of `f` & `args`."
  [f & args]
  (constantly (with-meta (apply f args) {:timestamp (t/now)})))

(defn exchange-rate
  "Return the exchange rate to DKK for the `currency` (identified by its code)."
  [currency]
  (when (hours-old @exchange-rates 8)
    (api-swap! exchange-rates (timestamped fetch-exchange-rates)))
  (get-in @exchange-rates [currency :rate]))

(defn fetch-prices
  "Request price data from 'Energi Data Service' according to `params`."
  [params]
  (:records (:body (client/get energy-api-url (assoc request-opts
                                                :query-params params
                                                :as :json)))))

(defn today-str
  "Today's date as 'YYYY-MM-dd' in CET."
  []
  (str (t/date (t/in (t/now) "CET"))))

(defn current-prices
  "Get current price data as a single collection according to `params`."
  [params]
  (when (hours-old (get @prices params) 1)
    (let [params' (assoc params :start (today-str))]
      (api-swap! prices update params (timestamped fetch-prices params'))))
  (get @prices params))

(defn- local-price
  "Get the local MWh price in `currency` from `raw-price-data`."
  [currency {:keys [SpotPriceEUR] :as raw-price-data}]
  (let [dkk-price (* SpotPriceEUR (/ (exchange-rate "EUR") 100))]
    (if (= currency "DKK")
      dkk-price
      (/ dkk-price (/ (exchange-rate currency) 100)))))

(defn- price-info
  "Get a timestamp + the spot price in `currency` for `raw-price-data`."
  [currency {:keys [HourDK PriceArea] :as raw-price-data}]
  (let [price-mwh (local-price currency raw-price-data)]
    {:timestamp (t/in (t/parse-date-time HourDK fmt) "CET")
     :currency  currency
     :region    PriceArea
     :price-mwh price-mwh
     :price-kwh (/ price-mwh 1000)}))

(defn normalize
  "Normalize `raw-prices` with respect to some `currency`."
  [currency raw-prices]
  (map (partial price-info currency) raw-prices))

(defn prices-below
  "Filter normalized `prices` with a currency/kWh below `n`."
  [n prices]
  (filter #(< (:price-kwh %) n) prices))

(defn- group-adjacent-helper
  [ret {:keys [timestamp] :as price}]
  (let [price-group  (peek ret)
        price-before (peek price-group)
        prev-ts      (:timestamp price-before)]
    (if (and prev-ts
             (t/= prev-ts (t/>> timestamp (t/new-duration 1 :hours))))
      (conj (vec (butlast ret)) (conj price-group price))
      (conj ret (list price)))))

(defn group-adjacent
  "Group normalized `prices` into collections of adjacent prices."
  [prices]
  (reverse (reduce group-adjacent-helper [] prices)))

(defn- daily-sorted-prices
  [prices]
  (-> (group-by (comp t/day-of-month :timestamp) prices)
      (update-vals (fn [prices] (sort-by :price-kwh prices)))))

(defn daily-minima
  "List each daily minimum price found in `prices`."
  [prices]
  (map first (vals (daily-sorted-prices prices))))

(defn daily-maxima
  "List each daily maximum price found in `prices`."
  [prices]
  (map last (vals (daily-sorted-prices prices))))

(comment
  ;; Example API request to get spot prices in Euro/MWh.
  (fetch-prices {:start  (today-str)
                 :sort   "HourDK DESC"
                 :filter (json/write-str {"PriceArea" "DK2"})})

  ;; Get currently relevant price data (fetched or from cache).
  (current-prices default-params)

  ;; Get the Euro->DKK exchange rate (fetched or from cache).
  (exchange-rate "EUR")

  ;; Filter cheap prices and group adjacent 1-hour blocks.
  (->> (current-prices default-params)
       (normalize "DKK")
       (prices-below 1.5)
       (group-adjacent))

  ;; Find the daily minima among the current prices.
  (->> (current-prices default-params)
       (normalize "DKK")
       (daily-minima))
  #_.)
