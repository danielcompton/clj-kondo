(ns clj-kondo.test-utils
  (:require
   [clj-kondo.impl.utils :refer [deep-merge]]
   [clj-kondo.main :as main :refer [main]]
   [clojure.java.io :as io]
   [clojure.string :as str :refer [trim]]
   [clojure.test :refer [is]]
   [me.raynes.conch :refer [programs with-programs let-programs] :as sh]))

(set! *warn-on-reflection* true)

(defn submap?
  "Is m1 a subset of m2? Taken from
  https://github.com/clojure/spec-alpha2, clojure.test-clojure.spec"
  [m1 m2]
  (if (and (map? m1) (map? m2))
    (every? (fn [[k v]] (and (contains? m2 k)
                             (submap? v (get m2 k))))
            m1)
    (= m1 m2)))

(defmacro assert-submap [m r]
  `(is (submap? ~m ~r)))

(defmacro assert-some-submap [m r]
  `(is (some #(submap? ~m %) ~r)))

(defmacro assert-submaps
  "Asserts that maps are submaps of result in corresponding order and
  that the number of maps corresponds to the number of
  results. Returns true if all assertions passed (useful for REPL)."
  [maps result]
  `(let [maps# ~maps
         res# ~result]
     (and
      (is (= (count maps#) (count res#)))
      (every? identity
              (for [[m# r#] (map vector maps# res#)]
                (assert-submap m# r#))))))

(defn parse-output
  "Parses linting output and prints everything that doesn't match the
  expected format (for debugging)."
  [msg]
  (doall
   (keep
    (fn [line]
      (if-let [[_ file row col level message] (re-matches #"(.+):(\d+):(\d+): (\w+): (.*)" line)]
        {:file file
         :row (Integer/parseInt row)
         :col (Integer/parseInt col)
         :level (keyword level)
         :message message}
        (when-not (str/starts-with? line "linting took")
          (println line))))
    (str/split-lines msg))))

(defn lint-jvm!
  ([input]
   (lint-jvm! input "--lang" "clj"))
  ([input & args]
   (let [[config args]
         (let [m (first args)]
           (if (map? m)
             [m (rest args)]
             [nil args]))
         base-config '{:linters {:unused-binding {:level :off}
                                 :unresolved-symbol {:level :off}}}
         config (str (deep-merge base-config config))
         res (with-out-str
               (try
                 (cond
                   (instance? java.io.File input)
                   (apply main "--lint" (.getPath ^java.io.File input) "--config" config args)
                   (vector? input)
                   (apply main "--lint" (concat (map #(.getPath ^java.io.File %) input)
                                                ["--config" config] args))
                   :else (with-in-str input
                           (apply main "--lint" "-"  "--config" config args)))
                 (catch Throwable e
                   (.printStackTrace e))))]
     (parse-output res))))

(defn lint-native!
  ([input] (lint-native! input "--lang" "clj"))
  ([input & args]
   (let [[config args]
         (let [m (first args)]
           (if (map? m)
             [m (rest args)]
             [nil args]))
         base-config '{:linters {:unused-binding {:level :off}
                                 :unresolved-symbol {:level :off}}}
         config (str (deep-merge base-config config))
         res (let-programs [clj-kondo "./clj-kondo"]
               (binding [sh/*throw* false]
                 (cond
                   (instance? java.io.File input)
                   (apply clj-kondo "--lint" (.getPath ^java.io.File input) "--config" config args)
                   (vector? input)
                   (apply clj-kondo "--lint" (concat (map #(.getPath ^java.io.File %) input)
                                                     ["--config" config] args))
                   :else
                   (apply clj-kondo  "--lint" "-" "--config" config
                          (conj (vec args)
                                ;; the opts go last
                                {:in input})))))]
     (parse-output res))))

(def lint!
  (case (System/getenv "CLJ_KONDO_TEST_ENV")
    "jvm" #'lint-jvm!
    "native" #'lint-native!
    lint-jvm!))

(if (= lint! lint-jvm!)
  (println "==== Testing JVM version")
  (println "==== Testing native version"))

(defn file-path
  "returns a file-path with platform specific file separator"
  [& more]
  (.getPath ^java.io.File (apply io/file more)))

;;;; Scratch

(comment
  (let-programs [clj-kondo "./clj-kondo"]
    (apply clj-kondo "--cache" ["--lint" "-" {:in "(defn foo [x] x) (foo 1 2 3)"}]))

  (lint-native! "(defn foo [x] x) (foo 1 2 3)")
  (lint-native! (io/file "test"))
  )
