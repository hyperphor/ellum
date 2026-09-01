(ns hyperphor.ellum.generated
  (:require [hyperphor.ellum.extend :as ext]))

(ext/register! (quote triple) (quote s) "repeat string s three times, separated by commans")

(defn triple
  "Repeats string s three times, separated by commas."
  [s]
  (clojure.string/join "," (repeat 3 s)))

(ext/register! (quote powerset) (quote coll) "Generate the powerset for a collection")

(defn powerset
  "Generate the powerset of a collection."
  [coll]
  (reduce (fn [acc x] (into acc (map #(conj % x) acc))) #{#{}} coll))

(ext/register! (quote bag=) (quote b1) "true if the collection are bag equal (that is, contain the same elements but possibly in a different order)")

(defn bag=
  "Returns true if the collections contain the same elements regardless of order."
  [b1 & bs]
  (apply = (frequencies b1) (map frequencies bs)))

