;;; Take a look at http://www.cs.utah.edu/~aek/code/init.el.html
;;; There are some interesting-looking settings in there.

;;; This is getting complicated enough that it seems like it just
;;; might be worth breaking into multiple modules to help with
;;; startup time. 
;;; Probably Better: Don't have so many instances!
;;; How's that work with multiple clojure projects/REPLs?

;;; Package Management.
(require 'package)
;; Apparently I want this if I'm going to be running
;; package-initialize myself
(setq package-enable-at-startup nil)
;; There are interesting debates about marmalade vs. melpa.
(add-to-list 'package-archives
             '("marmalade" . "http://marmalade-repo.org/packages/"))
(when nil (add-to-list 'package-archives
		       '("melpa" . "http://melpa.milkbox.net/packages/")))
(package-initialize)
(when (not package-archive-contents)
  (package-refresh-contents))

(defvar my-packages '(clojure-mode
		      clojure-test-mode
		      cider
		      ;;nrepl-ritz
		      paredit
		      ;;scala-mode2
		      ;; No such package...even though the package
		      ;; manager seemed to install it just fine
		      ;;scss
		      ))
(dolist (p my-packages)
  (when (not (package-installed-p p))
    (package-install p)))

;; Clojurescript files should be edited in clojure-mode
(add-to-list 'auto-mode-alist '("\.cljs$" . clojure-mode))
(add-to-list 'auto-mode-alist '("\.cljx$" . clojure-mode))

;; scss mode...seems odd that it hasn't made it into a package
;; index yet. Then again, maybe I just found this in ancient instructions
;(add-to-list 'load-path (expand-file-name "~/.emacs.d/utils/"))
;(autoload 'scss-mode "scss-mode")
;(add-to-list 'auto-mode-alist '("\\.scss\\'" . scss-mode))
(when nil (setq scss-compile-at-save nil))

(require 'magit)

;;; Clojure

;;; paredit
(add-hook 'emacs-lisp-mode-hook (lambda () (paredit-mode +1)))
(add-hook 'lisp-mode-hook (lambda () (paredit-mode +1)))
(add-hook 'lisp-interaction-mode-hook (lambda () (paredit-mode +1)))
(add-hook 'scheme-mode-hook (lambda () (paredit-mode +0)))
(add-hook 'clojure-mode-hook (lambda () (paredit-mode +1)))

(require 'eldoc)
(eldoc-add-command
  'paredit-backward-delete
  'paredit-close-round)

;; Recommendations from the nrepl README:

; eldoc (whatever that is):
(add-hook 'cider-interaction-mode-hook
          'cider-turn-on-eldoc-mode)

;; turn off auto-complete with tab
; (it recommends using M-tab instead)
(setq cider-tab-command 'indent-for-tab-command)

;; Make C-c C-z switch to *cider repl* in current window:
(when nil (add-to-list 'same-window-buffer-names "*nrepl*"))
(setq cider-repl-display-in-current-window t)

;; Camel Casing
(add-hook 'cider-mode-hook 'subword-mode)

;; paredit in nrepl (I'm very torn about this one):
;; No I'm not. Paredit's great for structuring code, but it leaves a lot to be
;; desired in interactive mode.
;; Especially under something like TMUX.
;;(add-hook 'nrepl-mode-hook 'paredit-mode)

;;; Ritz middleware
(when nil (require 'nrepl-ritz)
(define-key nrepl-interaction-mode-map (kbd "C-c C-j") 'nrepl-javadoc)
(define-key nrepl-mode-map (kbd "C-c C-j") 'nrepl-javadoc)
(define-key nrepl-interaction-mode-map (kbd "C-c C-a") 'nrepl-apropos)
(define-key nrepl-mode-map (kbd "C-c C-a") 'nrepl-apropos))

;;; org mode
(add-to-list 'load-path "/home/james/projects/programming/3rd/elisp/org-mode")
(setq org-todo-keywords
      '((sequence "TODO(t)" "STARTED(s)" "|" "DONE(d)" "DELEGATED(g)")
	(sequence "BUG(b)" "KNOWNCAUSE(k)" "|" "FIXED(f)")
	(sequence "|" "CANCELLED(c)" "DEFERRED(r)")))
; Mark the timestamp a task completed
(setq org-log-done 'time)

;; capture
(setq org-default-notes-file "~/projects/todo/notes.org")
(define-key global-map "\C-cn" 'org-capture)

;; Allow clojure code blocks in org mode
(require 'org)
(require 'ob-clojure)
(setq org-babel-clojure-backend 'cider)
(require 'cider)

;;; htmlize
;; TODO: How do I use external CSS?
(autoload 'htmlize-buffer "htmlize" 
  "Convert buffer to HTML, preserving colors and decorations." t)

;;; slime
;(load (expand-file-name "~/quicklisp/slime-helper.el"))
;(setq inferior-lisp-program "ccl")
;(load "~/quicklisp/log4slime-setup.el")
;(global-log4slime-mode 1)

;;; nrepl-ritz
(when nil
  (defun my-nrepl-mode-setup ()
    (require 'nrepl-ritz))
  (add-hook 'nrepl-interaction-mode-hook 'my-nrepl-mode-setup))

(eval-after-load 'tramp '(setenv "SHELL" "/bin/bash"))

;;; Ruby On Rails

;; Rake files are ruby too, as are gemspecs, rackup files, and gemfiles
(add-to-list 'auto-mode-alist '("\\.rake$" . ruby-mode))
(add-to-list 'auto-mode-alist '("Rakefile$" . ruby-mode))
(add-to-list 'auto-mode-alist '("\\.gemspec$" . ruby-mode))
(add-to-list 'auto-mode-alist '("\\.ru$" . ruby-mode))
(add-to-list 'auto-mode-alist '("Gemfile$" . ruby-mode))
(add-to-list 'auto-mode-alist '("Guardfile$" . ruby-mode))

;; Never want to edit bytecode
(add-to-list 'completion-ignored-extensions ".rbc")
(add-to-list 'completion-ignored-extensions ".pyc")

;; HAML...although the haml mode seems broken
(add-hook 'haml-mode-hook
	  (lambda ()
	    (setq indent-tabs-mode nil)
	    (define-key haml-mode-map "\C-m" 'newline-and-indent)))

;;; Auto-customized pieces

(custom-set-variables
 ;; custom-set-variables was added by Custom.
 ;; If you edit it by hand, you could mess it up, so be careful.
 ;; Your init file should contain only one such instance.
 ;; If there is more than one, they won't work right.
 )
(custom-set-faces
 ;; custom-set-faces was added by Custom.
 ;; If you edit it by hand, you could mess it up, so be careful.
 ;; Your init file should contain only one such instance.
 ;; If there is more than one, they won't work right.
 '(font-lock-builtin-face ((t (:foreground "green"))))
 '(font-lock-comment-face ((t (:foreground "cyan"))))
 '(font-lock-constant-face ((t (:foreground "red"))))
 '(font-lock-function-name-face ((t (:foreground "medium blue"))))
 '(font-lock-keyword-face ((t (:foreground "cyan"))))
 '(font-lock-string-face ((t (:foreground "color-88"))))
 '(font-lock-type-face ((t (:foreground "color-29"))))
 '(font-lock-variable-name-face ((t (:foreground "color-100"))))
 '(org-date ((t (:foreground "black" :underline t))))
 '(org-level-3 ((t (:inherit outline-3 :foreground "color-28"))))
 '(org-level-4 ((t (:inherit nil :foreground "color-54")))))

;; Configuration customizations for running under tmux
;; Found @ 
;; http://unix.stackexchange.com/questions/24414/shift-arrow-not-working-in-emacs-within-tmux
;; Seems to be needed when .tmux.conf includes
;; setw -g xterm-keys on
(if (getenv "TMUX")
    (progn
      (let ((x 2) (tkey ""))
	(while (<= x 8)
	  ;; shift
	  (if (= x 2)
	      (setq tkey "S-"))
	  ;; alt
	  (if (= x 3)
	      (setq tkey "M-"))
	  ;; alt + shift
	  (if (= x 4)
	      (setq tkey "M-S-"))
	  ;; ctrl
	  (if (= x 5)
	      (setq tkey "C-"))
	  ;; ctrl + shift
	  (if (= x 6)
	      (setq tkey "C-S-"))
	  ;; ctrl + alt
	  (if (= x 7)
	      (setq tkey "C-M-"))
	  ;; ctrl + alt + shift
	  (if (= x 8)
	      (setq tkey "C-M-S-"))

	  ;; arrows
	  (define-key key-translation-map 
	    (kbd (format "M-[ 1 ; %d A" x)) (kbd (format "%s<up>" tkey)))
	  (define-key key-translation-map 
	    (kbd (format "M-[ 1 ; %d B" x)) (kbd (format "%s<down>" tkey)))
	  (define-key key-translation-map
	    (kbd (format "M-[ 1 ; %d C" x)) (kbd (format "%s<right>" tkey)))
	  (define-key key-translation-map
	    (kbd (format "M-[ 1 ; %d D" x)) (kbd (format "%s<left>" tkey)))
	  ;; home
	  (define-key key-translation-map
	    (kbd (format "M-[ 1 ; %d H" x)) (kbd (format "%s<home>" tkey)))
	  ;; end
	  (define-key key-translation-map
	    (kbd (format "M-[ 1 ; %d F" x)) (kbd (format "%s<end>" tkey)))
	  ;; page up
	  (define-key key-translation-map
	    (kbd (format "M-[ 5 ; %d ~" x)) (kbd (format "%s<prior>" tkey)))
	  ;; page down
	  (define-key key-translation-map
	    (kbd (format "M-[ 6 ; %d ~" x)) (kbd (format "%s<next>" tkey)))
	  ;; insert
	  (define-key key-translation-map
	    (kbd (format "M-[ 2 ; %d ~" x)) (kbd (format "%s<delete>" tkey)))
	  ;; delete
	  (define-key key-translation-map
	    (kbd (format "M-[ 3 ; %d ~" x)) (kbd (format "%s<delete>" tkey)))
	  ;; f1
	  (define-key key-translation-map
	    (kbd (format "M-[ 1 ; %d P" x)) (kbd (format "%s<f1>" tkey)))
	  ;; f2
	  (define-key key-translation-map
	    (kbd (format "M-[ 1 ; %d Q" x)) (kbd (format "%s<f2>" tkey)))
	  ;; f3
	  (define-key key-translation-map
	    (kbd (format "M-[ 1 ; %d R" x)) (kbd (format "%s<f3>" tkey)))
	  ;; f4
	  (define-key key-translation-map
	    (kbd (format "M-[ 1 ; %d S" x)) (kbd (format "%s<f4>" tkey)))
	  ;; f5
	  (define-key key-translation-map
	    (kbd (format "M-[ 15 ; %d ~" x)) (kbd (format "%s<f5>" tkey)))
	  ;; f6
	  (define-key key-translation-map
	    (kbd (format "M-[ 17 ; %d ~" x)) (kbd (format "%s<f6>" tkey)))
	  ;; f7
	  (define-key key-translation-map
	    (kbd (format "M-[ 18 ; %d ~" x)) (kbd (format "%s<f7>" tkey)))
	  ;; f8
	  (define-key key-translation-map
	    (kbd (format "M-[ 19 ; %d ~" x)) (kbd (format "%s<f8>" tkey)))
	  ;; f9
	  (define-key key-translation-map
	    (kbd (format "M-[ 20 ; %d ~" x)) (kbd (format "%s<f9>" tkey)))
	  ;; f10
	  (define-key key-translation-map
	    (kbd (format "M-[ 21 ; %d ~" x)) (kbd (format "%s<f10>" tkey)))
	  ;; f11
	  (define-key key-translation-map
	    (kbd (format "M-[ 23 ; %d ~" x)) (kbd (format "%s<f11>" tkey)))
	  ;; f12
	  (define-key key-translation-map
	    (kbd (format "M-[ 24 ; %d ~" x)) (kbd (format "%s<f12>" tkey)))
	  ;; f13
	  (define-key key-translation-map
	    (kbd (format "M-[ 25 ; %d ~" x)) (kbd (format "%s<f13>" tkey)))
	  ;; f14
	  (define-key key-translation-map
	    (kbd (format "M-[ 26 ; %d ~" x)) (kbd (format "%s<f14>" tkey)))
	  ;; f15
	  (define-key key-translation-map
	    (kbd (format "M-[ 28 ; %d ~" x)) (kbd (format "%s<f15>" tkey)))
	  ;; f16
	  (define-key key-translation-map
	    (kbd (format "M-[ 29 ; %d ~" x)) (kbd (format "%s<f16>" tkey)))
	  ;; f17
	  (define-key key-translation-map
	    (kbd (format "M-[ 31 ; %d ~" x)) (kbd (format "%s<f17>" tkey)))
	  ;; f18
	  (define-key key-translation-map
	    (kbd (format "M-[ 32 ; %d ~" x)) (kbd (format "%s<f18>" tkey)))
	  ;; f19
	  (define-key key-translation-map
	    (kbd (format "M-[ 33 ; %d ~" x)) (kbd (format "%s<f19>" tkey)))
	  ;; f20
	  (define-key key-translation-map
	    (kbd (format "M-[ 34 ; %d ~" x)) (kbd (format "%s<f20>" tkey)))

	  (setq x (+ x 1))))))
