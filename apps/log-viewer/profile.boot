
(deftask cider "CIDER profile"
  []
  (require 'boot.repl)

  (swap! @(resolve 'boot.repl/*default-dependencies*)
         concat '[[cider/cider-nrepl "0.18.0" :scope "test"]
                  [com.billpiel/sayid "0.0.15" :scope "test"]
                  [refactor-nrepl "2.3.1" :scope "test"]])

  (swap! @(resolve 'boot.repl/*default-middleware*)
         concat
         '[cider.nrepl/cider-middleware
           refactor-nrepl.middleware/wrap-refactor])
  identity)
