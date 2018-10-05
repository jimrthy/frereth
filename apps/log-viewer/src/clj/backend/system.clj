(ns backend.system
  (:require [backend.web.server]))

(defn ctor [opts]
  {:backend.web.server/web-server opts})
