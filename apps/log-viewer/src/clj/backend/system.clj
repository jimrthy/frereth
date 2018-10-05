(ns backend.system
  (:require [backend.server]))

(defn ctor [opts]
  {:backend.server/web-server opts})
