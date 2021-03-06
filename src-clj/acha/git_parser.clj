(ns acha.git-parser
  (:require [acha.util :as util]
            [acha.core :as core]
            [clj-jgit.porcelain :as jgit.p]
            [clj-jgit.querying :as jgit.q]
            [clojure.java.io :as io]
            [clojure.tools.logging :as logging]
            [clojure.string :as string])
  (:import [java.security MessageDigest]
           [java.io ByteArrayOutputStream]
           [org.eclipse.jgit.diff DiffFormatter DiffEntry]
           [org.eclipse.jgit.diff RawTextComparator]
           [org.eclipse.jgit.lib ObjectReader]
           [org.eclipse.jgit.api Git]
           [org.eclipse.jgit.revwalk RevWalk RevCommit RevTree]
           [org.eclipse.jgit.lib Constants]
           [com.jcraft.jsch Session JSch]
           [org.eclipse.jgit.transport FetchResult JschConfigSessionFactory OpenSshConfig$Host SshSessionFactory]
           [org.eclipse.jgit.util FS]
           [org.eclipse.jgit.treewalk EmptyTreeIterator CanonicalTreeParser AbstractTreeIterator]))

(defn- data-dir [url]
  (let [repo-name (->> (string/split url #"/") (remove string/blank?) last)]
    (str core/working-dir "/" repo-name "_" (util/md5 url))))

(def jsch-factory (proxy [JschConfigSessionFactory] []
  (configure [^OpenSshConfig$Host hc ^Session session]
    (.getJSch ^JschConfigSessionFactory this hc FS/DETECTED))))

(SshSessionFactory/setInstance jsch-factory)

(defn- clone [url path]
  (->
    (doto (Git/cloneRepository)
      (.setURI url)
      (.setDirectory (io/as-file path))
      (.setRemote "origin")
      (.setCloneAllBranches true)
      (.setNoCheckout true))
    (.call)))

(defn load-repo [url]
  (let [path (data-dir url)]
    (if (.exists (io/as-file path))
      (doto (jgit.p/load-repo path)
        (jgit.p/git-fetch-all))
        (clone url path))))

(gen-interface
  :name achievement.git.IDiffStatsProvider
  :methods [[calculateDiffs [java.util.List] clojure.lang.APersistentMap]
            [treeIterator [org.eclipse.jgit.revwalk.RevCommit] org.eclipse.jgit.treewalk.AbstractTreeIterator ]])

(defn diff-formatter
  [^Git repo]
  (let [stats (atom {})
        stream (ByteArrayOutputStream.)
        reader (-> repo .getRepository .newObjectReader)
        diffs (atom [])
        section (atom [])
        formatter (proxy [DiffFormatter achievement.git.IDiffStatsProvider] [stream]
                    (writeAddedLine [text line]
                      (swap! section conj [:add (.getString text line) line])
                      (swap! stats update-in [:loc :added] (fnil inc 0)))
                    (writeRemovedLine [text line]
                      (swap! section conj [:remove (.getString text line) line])
                      (swap! stats update-in [:loc :deleted] (fnil inc 0)))
                    (writeHunkHeader [& _]
                      (swap! diffs conj @section)
                      (reset! section []))
                    (treeIterator [commit]
                      (if commit
                        (doto (CanonicalTreeParser.) (.reset reader (.getTree commit)))
                        (EmptyTreeIterator.)))
                    (calculateDiffs [diff-entities]
                      (reset! diffs [])
                      (reset! section [])
                      (reset! stats nil)
                      (.reset stream)
                      (proxy-super format diff-entities)
                      (proxy-super flush)
                      (when-not (empty? @section)
                        (swap! diffs conj @section))
                      (assoc @stats :diffs @diffs)))]
    (doto formatter
      (.setRepository (.getRepository repo))
      (.setDiffComparator RawTextComparator/DEFAULT))))

(defn- normalize-path [path]
  (cond
    (= path "/") "/"
    (= (first path) \/) (subs path 1)
    :else path))

(defn- change-kind
  [^DiffEntry entry]
  (let [change (.. entry getChangeType name)]
    (cond
      (= change "ADD") :add
      (= change "MODIFY") :edit
      (= change "DELETE") :delete
      (= change "COPY") :copy)))

(defn- parse-diff-entry
  [^DiffEntry entry]
  (let [change-kind (change-kind entry)
        old-file {:id (-> entry .getOldId .name)};, :path (normalize-path (.getOldPath entry))}
        new-file {:id (-> entry .getNewId .name), :path (normalize-path (.getNewPath entry))}]
    (case change-kind
      :edit   [change-kind old-file new-file]
      :add    [change-kind nil new-file]
      :delete [change-kind old-file nil]
      :else   [change-kind old-file new-file])))

(defn commit-info [^Git repo ^RevCommit rev-commit ^DiffFormatter df]
  (let [parent-tree (.treeIterator df (first (.getParents rev-commit)))
        commit-tree (.treeIterator df rev-commit)
        diffs (.scan df parent-tree commit-tree)
        ident (.getAuthorIdent rev-commit)
        time  (.getWhen ident)
        message (-> (.getFullMessage rev-commit) str string/trim)]
    (merge {:id (.getName rev-commit)
            :author (.getName ident)
            :email  (util/normalize-str (.getEmailAddress ident))
            :time time
            :timezone (.getTimeZone ident)
            :between-time (- (.getCommitTime rev-commit) (.getTime (.getWhen ident)))
            :message message
            :changed-files (mapv parse-diff-entry diffs)
            :merge (> (.getParentCount rev-commit) 1)}
           (.calculateDiffs df diffs))))

(def commit-list jgit.q/rev-list)
