(ns clj-arsenal.build
  (:require
   [clojure.tools.build.api :as b]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [deps-deploy.deps-deploy :as d])
  (:import java.io.File))

(def ^:private basis (delay (b/create-basis {:project "deps.edn"})))

(defn- read-meta
  []
  (-> (slurp "deps.edn")
    edn/read-string
    ::meta))

(defn- update-meta
  [f]
  ;; TODO: should probably lock it
  (as-> (slurp "deps.edn") $
    (edn/read-string $)
    (update $ ::meta f)
    (pr-str $)
    (spit "deps.edn" $)
    $))

(defn clean
  [_]
  (b/delete {:path "target"}))

(defn- bump
  [n]
  (let [build-meta (update-meta
                    (fn [build-meta]
                      (update build-meta :version
                        (fn [version]
                          (into (subvec version 0 n) (cons (inc (nth version n)) (subvec version (inc n))))))))
          version-str (str/join "." (:version build-meta))]
    (b/git-process {:git-args "stash"})
    (spit "meta.edn" (pr-str meta))
    (b/git-process {:git-args (str "commit -a -m v" version-str)})
    (b/git-process {:git-args (str "tag -a v" version-str " -m v" version-str)})
    (b/git-process {:git-args (str "push origin v" version-str)})))

(defn bump-patch [_]
  (bump 2))

(defn bump-minor [_]
  (bump 1))

(defn bump-major [_]
  (bump 0))

(defn pack [_]
  (run! io/delete-file (reverse (file-seq (File. "target"))))
  (let [{:keys [version name pub-url git-url license license-url]} (edn/read-string (slurp "meta.edn"))
        version-str (str/join "." version)
        class-dir "target/classes"]
    (b/write-pom
      {:class-dir class-dir
       :lib name
       :version version-str
       :basis @basis
       :src-dirs ["src"]
       :pom-data [[:licenses
                   [:license
                    [:name license]
                    [:url license-url]
                    [:distribution "repo"]]]
                  [:scm
                   [:url pub-url]
                   [:connection (str"scm:git:" git-url)]]]})
    (b/copy-dir
      {:src-dirs ["src"]
       :target-dir class-dir})
    (b/jar
      {:class-dir class-dir
       :jar-file (format "target/%s-%s.jar" (clojure.core/name name) version-str)})))

(defn deploy [_]
  (let [{:keys [version name]} (edn/read-string (slurp "meta.edn"))
        version-str (str/join "." version)]
    (d/deploy
      {:installer :remote
       :artifact (format "target/%s-%s.jar" (clojure.core/name name) version-str)
       :pom-file (str "target/classes/META-INF/maven/" (namespace name) "/" (clojure.core/name name) "/pom.xml")
       :sign-releases? true})))
