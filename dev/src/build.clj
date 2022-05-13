(ns build
  "A basic build script for creating an uberjar."
  (:require [org.corfield.build :as bb]))

(def lib 'dk.simongray/el)
(def main 'dk.simongray.el.calendar)

(defn ci
  "Run the CI pipeline of tests (and build the uberjar)."
  [opts]
  (-> opts
      (assoc :lib lib :main main)
      (bb/clean)
      (bb/uber)))

(comment
  (ci {:uber-file "el.jar"})
  #_.)
