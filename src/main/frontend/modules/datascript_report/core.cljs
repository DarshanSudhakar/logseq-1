(ns frontend.modules.datascript-report.core
  (:require [lambdaisland.glogi :as log]
            [datascript.core :as d]
            [datascript.db :as db]))

(def keys-of-deleted-entity 1)

(defn safe-pull
  [db selector eid]
  (try
    (d/pull db selector eid)
    (catch js/Error e
      (js/console.error e)
      nil)))

(defn get-entity-from-db-after-or-before
  [db-before db-after db-id]
  (let [r (safe-pull db-after '[*] db-id)]
    (if (= keys-of-deleted-entity (count r))
      ;; block has been deleted
      (safe-pull db-before '[*] db-id)
      r)))

(defn get-blocks-and-pages
  [{:keys [db-before db-after tx-data] :as _tx-report}]
  (let [updated-db-ids (-> (mapv first tx-data) (set))
        result (reduce
                (fn [acc x]
                  (let [block-entity
                        (get-entity-from-db-after-or-before db-before db-after x)
                        page-entity
                        (when-let [page-id (-> block-entity :block/page :db/id)]
                          (get-entity-from-db-after-or-before db-before db-after page-id))]
                    (cond-> acc
                      (some? block-entity)
                      (update :blocks conj block-entity)

                      (some? page-entity)
                      (update :pages conj page-entity))))
                {:blocks #{}
                 :pages #{}}
                updated-db-ids)]
    result))

(defn get-blocks
  [{:keys [db-before db-after tx-data] :as _tx-report}]
  (let [updated-db-ids (-> (mapv first tx-data) (set))]
    (reduce
      (fn [acc x]
        (let [block-entity
              (get-entity-from-db-after-or-before db-before db-after x)]
          (cond-> acc
            (some? block-entity)
            (conj block-entity))))
      []
      updated-db-ids)))
