(ns acha.core
  (:require
    [clojure.core.async :as async]))

(def version "0.2.1")

(def working-dir ".acha")

(def events (async/chan))
(def events-mult (async/mult events))
