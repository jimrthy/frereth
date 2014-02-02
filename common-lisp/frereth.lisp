;;;;
;;;; TODO: License statement here
;;;;

;;;; Frereth: main entry point
;;;; Honestly, this seems like a let-down.
;;;; There isn't much here, and really should never be.

(defun main (&args)
    (let ((repl (reader ns)))
    (do ((cmd (read))
	 (= cmd :quit)
	 (format t "Do something with: ~A" cmd)))))
