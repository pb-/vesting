(ns vesting
  (:require [clojure.string :as s]
            [clojure.edn :as edn])
  (:import [java.time LocalDate]))

(defn clamp [minimum x maximum]
  (max (min maximum x) minimum))

(defn parse-year-month [s]
  (LocalDate/parse (str s "-01")))

(defn month-range [start-inclusive end-exclusive]
  (let [months (.toTotalMonths (.until start-inclusive end-exclusive))]
    (for [i (range months)]
      (.plusMonths start-inclusive i))))

(defn value-at [grant month valuation]
  (let [shares (:shares grant)
        granted-at (parse-year-month (:granted-at grant))
        months-vested (.toTotalMonths (.until granted-at month))
        vesting-months (:vesting-months grant)
        cliff-months (:cliff-months grant 0)
        has-cliff? (pos? cliff-months)
        cliff-ends-at (.plusMonths granted-at cliff-months)
        shares-vested (* shares
                         (if has-cliff?
                           (cond
                             (.isBefore month cliff-ends-at) 0
                             (= month cliff-ends-at) (/ cliff-months vesting-months)
                             :else (min 1 (/ months-vested vesting-months)))
                           (clamp 0 (/ (inc months-vested) vesting-months) 1)))]
    (* shares-vested
       (- valuation (:strike-price-usd grant))
       (- 1 (/ (:tax-percent grant) 100)))))

(defn period [grant]
  (let [granted-at (parse-year-month (:granted-at grant))
        cliff-months (:cliff-months grant 0)]
    [(.plusMonths granted-at cliff-months)
     (.plusMonths granted-at (+ (:vesting-months grant) (min cliff-months 1)))]))

(defn make-schedule [grants valuation]
  (let [periods (map period grants)
        start (first (sort (map first periods)))
        end (last (sort (map second periods)))]
    (cons (cons "Month" (map :name grants))
          (for [month (month-range start end)]
            (cons month
                  (for [grant grants]
                    (value-at grant month valuation)))))))

(defn format-usd [x]
  (double (/ (Math/round (double (* 100 x))) 100)))

(defn print-gnuplot! [schedule]
  (let [header (first schedule)
        data (rest schedule)]
    (println "$data <<EOD")
    (println (s/join \tab (map pr-str header)))
    (doseq [[month & gains] data]
      (println (s/join \tab
                       (cons (subs (str month) 0 7)
                             (for [usd gains] (format-usd usd))))))
    (println "EOD")
    (println "set style data histograms")
    (println "set style histogram rowstacked")
    (println "set auto x")
    (println "unset xtics")
    (println "set xtics nomirror rotate by -45 scale 0")
    (println "set xtics font ', 10'")
    (println "set key autotitle columnheader")
    (println "set key left")
    (println "set style fill solid")
    (println "set boxwidth 0.75")
    (println "set grid ytics")
    (println "set decimal locale")
    (println "set format y \"$%'g\"")
    (println "set title 'Estimated after-tax gains'")
    (println "set terminal png size 1900,700 enhanced")
    (println (str "plot $data using 2:xtic(1), for [i=3:" (count header) "] '' using i"))))

(defn -main [grant-file & [valuation]]
  (let [input (edn/read-string (slurp grant-file))]
    (print-gnuplot!
      (make-schedule
        (:grants input)
        (if valuation
          (Float/parseFloat valuation)
          (:valuation-usd input))))))
