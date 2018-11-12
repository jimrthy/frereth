(ns common.hello)

(defn foo-cljc
  "I don't do a whole lot."
  [x]
  (str "Hello from " #?(:clj "clj" :cljs "cljs") " " x "!"))
