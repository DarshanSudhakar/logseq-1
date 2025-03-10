(ns frontend.fs.watcher-handler
  (:require [clojure.core.async :as async]
            [lambdaisland.glogi :as log]
            [frontend.handler.file :as file-handler]
            [frontend.handler.page :as page-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.route :as route-handler]
            [cljs-time.coerce :as tc]
            [frontend.config :as config]
            [frontend.db :as db]
            [frontend.state :as state]
            [clojure.string :as string]
            [frontend.encrypt :as encrypt]
            [frontend.db.model :as model]
            [frontend.handler.editor :as editor]
            [frontend.handler.extract :as extract]
            [promesa.core :as p]
            [electron.ipc :as ipc]))

(defn- set-missing-block-ids!
  [content]
  (when (string? content)
    (doseq [block-id (extract/extract-all-block-refs content)]
      (when-let [block (try
                         (model/get-block-by-uuid block-id)
                         (catch js/Error _e
                           nil))]
        (let [id-property (:id (:block/properties block))]
          (when-not (= (str id-property) (str block-id))
            (editor/set-block-property! block-id "id" block-id)))))))

(def ignore-files (atom {}))

(defn handle-changed!
  [type {:keys [dir path content stat] :as payload}]
  (when dir
    (let [repo (config/get-local-repo dir)
          {:keys [mtime]} stat
          db-content (or (db/get-file repo path) "")]
      (when (and content (not (encrypt/content-encrypted? content)))
        (cond
          (= "add" type)
          (when-not (db/file-exists? repo path)
            (p/let [_ (file-handler/alter-file repo path content {:re-render-root? true
                                                                  :from-disk? true})]
              (set-missing-block-ids! content)
              (db/set-file-last-modified-at! repo path mtime)
              ;; return nil, otherwise the entire db will be transfered by ipc
              nil))

          (and (= "change" type)
               (not (db/file-exists? repo path)))
          (js/console.warn "Can't get file in the db: " path)

          (and (= "change" type)
               ;; ignore truncate
               (not (string/blank? content))
               (not= (string/trim content)
                     (string/trim (or (db/get-file repo path) "")))
               (not (string/includes? path "logseq/pages-metadata.edn"))
               (not= (get @ignore-files path) content))
          (p/let [
                  ;; save the previous content in Logseq first and commit it to avoid
                  ;; any data-loss.
                  _ (swap! ignore-files assoc path db-content)
                  _ (file-handler/alter-file repo path db-content {:re-render-root? false
                                                                   :reset? false
                                                                   :skip-compare? true})
                  result (ipc/ipc "gitCommitAll" "")
                  _ (file-handler/alter-file repo path content {:re-render-root? true
                                                                :from-disk? true})]
            (set-missing-block-ids! content)
            (db/set-file-last-modified-at! repo path mtime)
            (js/setTimeout (fn []
                             (when (= db-content (get @ignore-files path))
                               (swap! ignore-files dissoc path))) 2000)
            nil)

          (contains? #{"add" "change" "unlink"} type)
          nil

          :else
          (log/error :fs/watcher-no-handler {:type type
                                             :payload payload}))))))
