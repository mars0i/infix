;; The MIT License (MIT)
;;
;; Copyright (c) 2016 Richard Hull
;;
;; Permission is hereby granted, free of charge, to any person obtaining a copy
;; of this software and associated documentation files (the "Software"), to deal
;; in the Software without restriction, including without limitation the rights
;; to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
;; copies of the Software, and to permit persons to whom the Software is
;; furnished to do so, subject to the following conditions:
;;
;; The above copyright notice and this permission notice shall be included in all
;; copies or substantial portions of the Software.
;;
;; THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
;; IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
;; FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
;; AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
;; LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
;; OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
;; SOFTWARE.

(ns infix.grammar
  (:require
    [jasentaa.monad :as m]
    [jasentaa.parser.basic :refer :all]
    [jasentaa.parser.combinators :refer :all]))

; expression ::= term { addop term }.
; term ::= factor { mulop factor }.
; factor ::= "(" expression ")" | var | number | function.
; addop ::= "+" | "-".
; mulop ::= "*" | "/".
;
; function ::= envref expression | envref "(" <empty> | expression { "," expression } ")".
; envref ::= letter { letter | digit | "_" }.
; var ::= envref.
; number ::= integer | decimal | rational | binary | hex
; binary :: = [ "-" ] "0b" { "0" | "1" }.
; hex :: = [ "-" ] "0x" | "#" { "0" | ... | "9" | "A" | ... | "F" | "a" | ... | "f" }.
; integer :: = [ "-" ] digits.
; decimal :: = [ "-" ] digits "." digits.
; rational :: = integer "/" digits.
; letter ::= "A" | "B" | ... | "Z" | "a" | "b" | ... | "z".
; digit ::= "0" | "1" | ... | "8" | "9".
; digits ::= digit { digit }.

(def digit (from-re #"[0-9]"))

; TODO: allow unicode/utf8 characters
(def letter (from-re #"[a-zA-Z]"))

(def alpha-num (any-of letter digit (match "_")))

(def digits
  (m/do*
    (text <- (plus digit))
    (m/return (apply str text))))

(def envref
  (m/do*
   (fst <- letter)
   (rst <- (many alpha-num))
   (m/return (let [kw (keyword (apply str (cons fst rst)))]
             (fn [env]
               (if (contains? env kw)
                 (get env kw)
                 (throw (IllegalStateException.
                          (str (name kw) " is not bound in environment")))))))))

(def var envref)

(def binary
  (m/do*
    (sign <- (optional (match "-")))
    (string "0b")
    (value <- (m/do*
                (text <- (plus (from-re #"[01]")))
                (m/return (apply str text))))
    (m/return (constantly (Long/parseLong (str sign value) 2)))))

(def hex
  (m/do*
    (sign <- (optional (match "-")))
    (any-of
      (match "#")
      (string "0x"))
    (value <- (m/do*
                (text <- (plus (from-re #"[0-9A-Fa-f]")))
                (m/return (apply str text))))
    (m/return (constantly (Long/parseLong (str sign value) 16)))))

(def integer
  (m/do*
    (sign <- (optional (match "-")))
    (value <- digits)
    (m/return (constantly (Long/parseLong (str sign value))))))

(def rational
  (m/do*
    (dividend <- integer)
    (match "/")
    (divisor <- digits)
    (m/return (constantly (/ (dividend) (Long/parseLong divisor))))))

(def decimal
  (m/do*
    (sign <- (optional (match "-")))
    (i <- digits)
    (p <- (match "."))
    (d <- digits)
    (m/return (constantly (Double/parseDouble (str sign i p d))))))

(def number
  (any-of integer decimal rational binary hex))

(defn list-of [parser]
  (m/do*
    (fst <- parser)
    (rst <- (optional
              (m/do*
                spaces
                (match ",")
                spaces
                (list-of parser))))
    (m/return (cons fst rst))))

(declare expression)

(def function
  (or-else
    (m/do*
      (f <- envref)
      (plus (match " "))
      (expr <- expression)
      (m/return (fn [env]
                ((f env) (expr env)))))
    (m/do*
      (f <- envref)
      (match "(")
      spaces
      (args <- (or-else (list-of expression) (m/return nil)))
      spaces
      (match ")")
      (m/return (fn [env]
                (apply
                  (f env)
                  (map #(% env) args)))))))

(def factor
  (any-of
    (m/do*
      (match "(")
      spaces
      (e <- expression)
      spaces
      (match ")")
      (m/return e))
    var
    number
    function))

(defn binary-op [& ops]
  (m/do*
    (op <- (reduce or-else (map string ops)))
    (m/return
      (let [kw (keyword (str op))]
        (fn [env]
          (if (contains? env kw)
            (get env kw)
            (throw (IllegalStateException.
                     (str (name kw) " is not bound in environment")))))))))

(def mulop (binary-op "*" "/" "÷" "**" "%" ">>" ">>>" "<<"))
(def addop (binary-op "+" "-" "|" "&"))

(defn- resolve-var [arg env]
  (let [v (arg env)]
    (if (var? v)
      (var-get v)
      v)))

(defn- binary-reducer [op-parser arg-parser]
  (m/do*
    (a1 <- arg-parser)
    (rst <- (many
              (m/do*
                spaces
                (op <- op-parser)
                spaces
                (a2 <- arg-parser)
                (m/return [op a2]))))
    (m/return
      (fn [env]
        (reduce
          (fn [acc [op a2]] ((op env) acc (resolve-var a2 env)))
          (resolve-var a1 env)
          rst)))))

(def term (binary-reducer mulop factor))

(def expression (binary-reducer addop term))
