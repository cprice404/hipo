(ns hipo.compiler
  (:require [clojure.string :as str]
            [cljs.analyzer.api :as ana]))

(def +svg-ns+ "http://www.w3.org/2000/svg")
(def +svg-tags+ #{"svg" "g" "rect" "circle" "clipPath" "path" "line" "polygon" "polyline" "text" "textPath"})

(defn literal?
  [o]
  (or (string? o) (number? o) (true? o) (false? o)))

(defmulti compile-set-attribute! (fn [_ a _] a))

(defmethod compile-set-attribute! :default
  [el a v]
  (cond
    (= a "id")
    `(set! (.-id ~el) ~v)
    (= 0 (.indexOf a "on-"))
    (let [e (.substring a 3)] `(.addEventListener ~el ~e ~v))
    :else
    `(.setAttribute ~el ~a ~v)))

(defmacro compile-set-attribute!*
  "compile-time set attribute"
  [el k v]
  {:pre [(keyword? k)]}
  (let [a (name k)]
    (if (literal? v)
      (if v
        (compile-set-attribute! el a v))
      (let [ve (gensym "v")]
        `(let [~ve ~v]
           (if ~ve
             ~(compile-set-attribute! el a ve)))))))

(defn parse-keyword
  "return pair [tag class-str id] where tag is dom tag and attrs
   are key-value attribute pairs from css-style dom selector"
  [node-key]
  (let [node-str (name node-key)
        node-tag (second (re-find #"^([^.\#]+)[.\#]?" node-str))
        classes (map #(.substring ^String % 1) (re-seq #"\.[^.*]*" node-str))
        id (first (map #(.substring ^String % 1) (re-seq #"#[^.*]*" node-str)))]
    [(if (empty? node-tag) "div" node-tag)
     (when-not (empty? classes) (str/join " " classes))
     id]))

(defmacro compile-create-element
  [namespace-uri tag]
  (if namespace-uri
    `(.createElementNS js/document ~namespace-uri ~tag)
    `(.createElement js/document ~tag)))

(defn- form-name
  [form]
  (if (and (seq? form) (symbol? (first form)))
    (name (first form))))

(defmulti compile-append-form
  #(form-name (second %)))

(defmethod compile-append-form "for"
  [[el [_ bindings body]]]
  `(doseq ~bindings (compile-append-child ~el ~body)))

(defmethod compile-append-form "if"
  [[el [_ condition & body]]]
  (if (= 1 (count body))
    `(if ~condition (compile-append-child ~el ~(first body)))
    `(if ~condition (compile-append-child ~el ~(first body))
                    (compile-append-child ~el ~(second body)))))

(defmethod compile-append-form "when"
  [[el [_ condition & body]]]
  (assert (= 1 (count body)) "Only a single form is supported with when")
  `(if ~condition (compile-append-child ~el ~(last body))))

(defmethod compile-append-form "list"
  [[el [_ & body]]]
  `(do ~@(for [o body] `(compile-append-child ~el ~o))))

(defmethod compile-append-form :default
  [[el o]]
  (when o
    `(let [o# ~o]
       (if o#
         (hipo.interpreter/append-to-parent ~el o#)))))

(defn text-compliant-hint?
  [data env]
  (when (seq? data)
    (when-let [f (first data)]
      (when (symbol? f)
        (let [t (:tag (ana/resolve env f))]
          (or (= t 'boolean)
              (= t 'string)
              (= t 'number)))))))

(defn text-content?
  [data env]
  (or (literal? data)
      (-> data meta :text)
      (text-compliant-hint? data env)))

(defmacro compile-append-child
  [el data]
  (cond
    (text-content? data &env) `(.appendChild ~el (.createTextNode js/document ~data))
    (vector? data) `(.appendChild ~el (compile-create-vector ~data))
    :else (compile-append-form [el data])))

(defn compile-class
  [literal-attrs class-keyword]
  (let [literal-class (:class literal-attrs)]
    (if class-keyword
      (cond
        (nil? literal-class) class-keyword
        (string? literal-class) (str class-keyword " " literal-class)
        :else `(str ~(str class-keyword " ") ~literal-class))
      literal-class)))

(defmacro compile-create-vector
  [[node-key & rest]]
  (let [literal-attrs (when-let [f (first rest)] (when (map? f) f))
        var-attrs (when (and (not literal-attrs) (-> rest first meta :attrs))
                    (first rest))
        children (if (or literal-attrs var-attrs) (drop 1 rest) rest)
        [tag class-keyword id-keyword] (parse-keyword node-key)
        class (compile-class literal-attrs class-keyword)
        el (gensym "el")
        element-ns (when (+svg-tags+ tag) +svg-ns+)]
    (if (and (nil? rest) (nil? id-keyword) (empty? class-keyword))
      `(compile-create-element ~element-ns ~tag)
    `(let [~el (compile-create-element ~element-ns ~tag)]
       ~(when id-keyword
          `(set! (.-id ~el) ~id-keyword))
       ~(when class
          `(.setAttribute ~el "class" ~class))
       ~@(for [[k v] (dissoc literal-attrs :class)]
           `(compile-set-attribute!* ~el ~k ~v))
       ~(when var-attrs
          (let [k (gensym "k")
                v (gensym "v")]
            `(doseq [[~k ~v] ~var-attrs]
               (when ~v
                 ~(if class
                    `(if (= :class ~k)
                       (set! (.-className ~el) (str ~(str class " ") ~v))
                       (hipo.interpreter/set-attribute! ~el (name ~k) nil ~v))
                    `(hipo.interpreter/set-attribute! ~el (name ~k) nil ~v))))))
       ~@(when (seq children)
          (if (every? #(text-content? % &env) children)
            `[(set! (.-textContent ~el) (str ~@children))]
            (for [c (filter identity children)]
              `(compile-append-child ~el ~c))))
       ~el))))

(defmacro compile-create
  [o]
  (cond
    (text-content? o &env) `(.createTextNode js/document ~o)
    (vector? o) `(compile-create-vector ~o)
    :else `(hipo.interpreter/create ~o)))
