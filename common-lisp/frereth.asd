;;; frereth.asd
;;; TODO: Pick a license

(asdf:defsystem #:frereth
    :serial t
    :description "Gluing together the pieces"
    :author "jamesgatannah@gmail.com"
    :license "AGPL"  ;; Is that legal?
    :depends-on (#:frereth-renderer)
    :components ((:file "package")
		 (:file "frereth")))
