(ns clj-arsenal.build
  (:require
   [clojure.tools.build.api :as b]
   [clojure.string :as str]
   [clojure.edn :as edn]
   [deps-deploy.deps-deploy :as d]
   [rewrite-clj.zip :as z])
  (:import java.io.File java.nio.file.Files))

(defn- update-deps!
  [f]
  (spit "deps.edn" (z/root-string (f (z/of-file "deps.edn")))))

(defn clean
  [_]
  (b/delete {:path "target"}))

(defn- bump
  [n]
  (b/git-process {:git-args "stash"})
  (update-deps!
    (fn [zloc]
      (-> zloc
        (z/get ::meta)
        (z/get :version)
        (z/edit
          (fn [version-str]
            (let [version-vec (mapv parse-long (str/split version-str #"[.]"))]
              (str/join "."
                (into (subvec version-vec 0 n)
                  (cons (inc (nth version-vec n))
                    (subvec version-vec (inc n)))))))))))
  (let [{:keys [version]} (::meta (edn/read-string (slurp "deps.edn")))]
    (b/git-process {:git-args (str "commit -a -m v" version)})
    (b/git-process {:git-args (str "tag -a v" version " -m v" version)})
    (b/git-process {:git-args (str "push --follow-tags")})))

(defn bump-patch [_]
  (bump 2))

(defn bump-minor [_]
  (bump 1))

(defn bump-major [_]
  (bump 0))

(defn pack [_]
  (run! #(Files/deleteIfExists (.toPath ^File %)) (reverse (file-seq (File. "target"))))
  (let [basis (b/create-basis {:project "deps.edn"})
        {:keys [name version license license-url pub-url git-url]} (::meta basis)
        class-dir "target/classes"]
    (b/write-pom
      {:class-dir class-dir
       :lib name
       :version version
       :basis basis
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
       :jar-file (format "target/%s-%s.jar" (clojure.core/name name) version)})))

(defn deploy [_]
  (let [basis (b/create-basis {:project "deps.edn"})
        {:keys [version name]} (::meta basis) 
        version-str (str/join "." version)]
    (d/deploy
      {:installer :remote
       :artifact (format "target/%s-%s.jar" (clojure.core/name name) version-str)
       :pom-file (str "target/classes/META-INF/maven/" (namespace name) "/" (clojure.core/name name) "/pom.xml")
       :sign-releases? true})))
