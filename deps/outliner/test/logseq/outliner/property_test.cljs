(ns logseq.outliner.property-test
  (:require [cljs.test :refer [deftest is testing are]]
            [datascript.core :as d]
            [logseq.db :as ldb]
            [logseq.db.frontend.property :as db-property]
            [logseq.db.test.helper :as db-test]
            [logseq.outliner.property :as outliner-property]))

(deftest upsert-property!
  (testing "Creates a property"
    (let [conn (db-test/create-conn-with-blocks [])
          _ (outliner-property/upsert-property! conn nil {:logseq.property/type :number} {:property-name "num"})]
      (is (= :number
             (:logseq.property/type (d/entity @conn :user.property/num)))
          "Creates property with property-name")))

  (testing "Updates a property"
    (let [conn (db-test/create-conn-with-blocks {:properties {:num {:logseq.property/type :number}}})
          old-updated-at (:block/updated-at (d/entity @conn :user.property/num))]

      (testing "and change its cardinality"
        (outliner-property/upsert-property! conn :user.property/num {:db/cardinality :many} {})
        (is (db-property/many? (d/entity @conn :user.property/num)))
        (is (> (:block/updated-at (d/entity @conn :user.property/num))
               old-updated-at)))

      (testing "and change its type from a ref to a non-ref type"
        (outliner-property/upsert-property! conn :user.property/num {:logseq.property/type :checkbox} {})
        (is (= :checkbox (:logseq.property/type (d/entity @conn :user.property/num))))
        (is (= nil (:db/valueType (d/entity @conn :user.property/num)))))))

  (testing "Multiple properties that generate the same initial :db/ident"
    (let [conn (db-test/create-conn-with-blocks [])]
      (outliner-property/upsert-property! conn nil {:logseq.property/type :default} {:property-name "p1"})
      (outliner-property/upsert-property! conn nil {} {:property-name "p1"})
      (outliner-property/upsert-property! conn nil {} {:property-name "p1"})

      (is (= {:block/name "p1" :block/title "p1" :logseq.property/type :default}
             (select-keys (d/entity @conn :user.property/p1) [:block/name :block/title :logseq.property/type]))
          "Existing db/ident does not get modified")
      (is (= "p1"
             (:block/title (d/entity @conn :user.property/p1-1)))
          "2nd property gets unique ident")
      (is (= "p1"
             (:block/title (d/entity @conn :user.property/p1-2)))
          "3rd property gets unique ident"))))

(deftest convert-property-input-string
  (testing "Convert property input string according to its schema type"
    (let [test-uuid (random-uuid)]
      (are [x y]
           (= (let [[schema-type value] x]
                (outliner-property/convert-property-input-string nil {:logseq.property/type schema-type} value)) y)
        [:number "1"] 1
        [:number "1.2"] 1.2
        [:url test-uuid] test-uuid
        [:date test-uuid] test-uuid
        [:any test-uuid] test-uuid
        [nil test-uuid] test-uuid))))

(deftest create-property-text-block!
  (testing "Create a new :default property value"
    (let [conn (db-test/create-conn-with-blocks
                [{:page {:block/title "page1"}
                  :blocks [{:block/title "b1" :build/properties {:default "foo"}}
                           {:block/title "b2"}]}])
          block (db-test/find-block-by-content @conn "b2")
          ;; Use same args as outliner.op
          _ (outliner-property/create-property-text-block! conn (:db/id block) :user.property/default "" {})
          new-property-value (:user.property/default (db-test/find-block-by-content @conn "b2"))]

      (is (some? (:db/id new-property-value)) "New property value created")
      (is (= "" (db-property/property-value-content new-property-value))
          "Property value has correct content")
      (is (= :user.property/default
             (get-in (d/entity @conn (:db/id new-property-value)) [:logseq.property/created-from-property :db/ident]))
          "Has correct created-from-property")))

  (testing "Create cases for a new :one :number property value"
    (let [conn (db-test/create-conn-with-blocks
                [{:page {:block/title "page1"}
                  :blocks [{:block/title "b1" :build/properties {:num 2}}
                           {:block/title "b2"}]}])
          block (db-test/find-block-by-content @conn "b2")
          ;; Use same args as outliner.op
          _ (outliner-property/create-property-text-block! conn (:db/id block) :user.property/num "3" {})
          new-property-value (:user.property/num (db-test/find-block-by-content @conn "b2"))]

      (is (some? (:db/id new-property-value)) "New property value created")
      (is (= 3 (db-property/property-value-content new-property-value))
          "Property value has correct content")
      (is (= :user.property/num
             (get-in (d/entity @conn (:db/id new-property-value)) [:logseq.property/created-from-property :db/ident]))
          "Has correct created-from-property")

      (is (thrown-with-msg?
           js/Error
           #"Can't convert"
           (outliner-property/create-property-text-block! conn (:db/id block) :user.property/num "Not a number" {}))
          "Wrong value isn't transacted")))

  (testing "Create new :many :number property values"
    (let [conn (db-test/create-conn-with-blocks
                [{:page {:block/title "page1"}
                  :blocks [{:block/title "b1" :build/properties {:num-many #{2}}}
                           {:block/title "b2"}]}])
          block (db-test/find-block-by-content @conn "b2")
          ;; Use same args as outliner.op
          _ (outliner-property/create-property-text-block! conn (:db/id block) :user.property/num-many "3" {})
          _ (outliner-property/create-property-text-block! conn (:db/id block) :user.property/num-many "4" {})
          _ (outliner-property/create-property-text-block! conn (:db/id block) :user.property/num-many "5" {})
          new-property-values (:user.property/num-many (db-test/find-block-by-content @conn "b2"))]

      (is (seq new-property-values) "New property values created")
      (is (= #{3 4 5} (set (map db-property/property-value-content new-property-values)))
          "Property value has correct content"))))

(deftest set-block-property-basic-cases
  (testing "Set a :number value with existing value"
    (let [conn (db-test/create-conn-with-blocks
                [{:page {:block/title "page1"}
                  :blocks [{:block/title "b1" :build/properties {:num 2}}
                           {:block/title "b2"}]}])
          property-value (:user.property/num (db-test/find-block-by-content @conn "b1"))
          _ (assert (:db/id property-value))
          block-uuid (:block/uuid (db-test/find-block-by-content @conn "b2"))
          ;; Use same args as outliner.op
          _ (outliner-property/set-block-property! conn [:block/uuid block-uuid] :user.property/num (:db/id property-value))]
      (is (= (:db/id property-value)
             (:db/id (:user.property/num (db-test/find-block-by-content @conn "b2")))))))

  (testing "Update a :number value with existing value"
    (let [conn (db-test/create-conn-with-blocks
                [{:page {:block/title "page1"}
                  :blocks [{:block/title "b1" :build/properties {:num 2}}
                           {:block/title "b2" :build/properties {:num 3}}]}])
          property-value (:user.property/num (db-test/find-block-by-content @conn "b1"))
          _ (assert (:db/id property-value))
          block-uuid (:block/uuid (db-test/find-block-by-content @conn "b2"))
          ;; Use same args as outliner.op
          _ (outliner-property/set-block-property! conn [:block/uuid block-uuid] :user.property/num (:db/id property-value))]
      (is (= (:db/id property-value)
             (:db/id (:user.property/num (db-test/find-block-by-content @conn "b2"))))))))

(deftest set-block-property-with-non-ref-values
  (testing "Setting :default with same property value reuses existing entity"
    (let [conn (db-test/create-conn-with-blocks
                [{:page {:block/title "page1"}
                  :blocks [{:block/title "b1" :build/properties {:logseq.property/order-list-type "number"}}
                           {:block/title "b2"}]}])
          property-value (:logseq.property/order-list-type (db-test/find-block-by-content @conn "b1"))
          block-uuid (:block/uuid (db-test/find-block-by-content @conn "b2"))
          ;; Use same args as outliner.op
          _ (outliner-property/set-block-property! conn [:block/uuid block-uuid] :logseq.property/order-list-type "number")]
      (is (some? (:db/id (:logseq.property/order-list-type (db-test/find-block-by-content @conn "b2"))))
          "New block has property set")
      (is (= (:db/id property-value)
             (:db/id (:logseq.property/order-list-type (db-test/find-block-by-content @conn "b2")))))))

  (testing "Setting :checkbox with same property value reuses existing entity"
    (let [conn (db-test/create-conn-with-blocks
                [{:page {:block/title "page1"}
                  :blocks [{:block/title "b1" :build/properties {:checkbox true}}
                           {:block/title "b2"}]}])
          property-value (:user.property/checkbox (db-test/find-block-by-content @conn "b1"))
          block-uuid (:block/uuid (db-test/find-block-by-content @conn "b2"))
          ;; Use same args as outliner.op
          _ (outliner-property/set-block-property! conn [:block/uuid block-uuid] :user.property/checkbox true)]
      (is (true? (:user.property/checkbox (db-test/find-block-by-content @conn "b2")))
          "New block has property set")
      (is (= property-value (:user.property/checkbox (db-test/find-block-by-content @conn "b2")))))))

(deftest remove-block-property!
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}
                :blocks [{:block/title "b1" :build/properties {:default "foo"}}]}])
        block (db-test/find-block-by-content @conn "b1")
        _ (assert (:user.property/default block))
        ;; Use same args as outliner.op
        _ (outliner-property/remove-block-property! conn [:block/uuid (:block/uuid block)] :user.property/default)
        updated-block (db-test/find-block-by-content @conn "b1")]
    (is (some? updated-block))
    (is (nil? (:user.property/default updated-block)) "Block property is deleted")))

(deftest batch-set-property!
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}
                :blocks [{:block/title "item 1"}
                         {:block/title "item 2"}]}])
        block-ids (map #(-> (db-test/find-block-by-content @conn %) :block/uuid) ["item 1" "item 2"])
        _ (outliner-property/batch-set-property! conn block-ids :logseq.property/order-list-type "number")
        updated-blocks (map #(db-test/find-block-by-content @conn %) ["item 1" "item 2"])]
    (is (= ["number" "number"]
           (map #(db-property/property-value-content (:logseq.property/order-list-type %))
                updated-blocks))
        "Property values are batch set")))

(deftest status-property-setting-classes
  (let [conn (db-test/create-conn-with-blocks
              {:classes {:Project {:build/class-properties [:logseq.property/status]}}
               :pages-and-blocks
               [{:page {:block/title "page1"}
                 :blocks [{:block/title ""}
                          {:block/title "project task" :build/tags [:Project]}]}]})
        page1 (:block/uuid (db-test/find-page-by-title @conn "page1"))
        [empty-task project]
        (map #(:block/uuid (db-test/find-block-by-content @conn %)) ["" "project task"])]

    (outliner-property/batch-set-property! conn [empty-task] :logseq.property/status :logseq.property/status.doing)
    (is (= [:logseq.class/Task]
           (mapv :db/ident (:block/tags (d/entity @conn [:block/uuid empty-task]))))
        "Adds Task to block when it is not tagged")

    (outliner-property/batch-set-property! conn [page1] :logseq.property/status :logseq.property/status.doing)
    (is (= #{:logseq.class/Task :logseq.class/Page}
           (set (map :db/ident (:block/tags (d/entity @conn [:block/uuid page1])))))
        "Adds Task to page without tag")

    (outliner-property/batch-set-property! conn [project] :logseq.property/status :logseq.property/status.doing)
    (is (= [:user.class/Project]
           (mapv :db/ident (:block/tags (d/entity @conn [:block/uuid project]))))
        "Doesn't add Task to block when it is already tagged")))

(deftest batch-remove-property!
  (let [conn (db-test/create-conn-with-blocks
              {:classes {:C1 {}}
               :pages-and-blocks
               [{:page {:block/title "page1"}
                 :blocks [{:block/title "item 1" :build/properties {:logseq.property/order-list-type "number"}}
                          {:block/title "item 2" :build/properties {:logseq.property/order-list-type "number"}}]}]})
        block-ids (map #(-> (db-test/find-block-by-content @conn %) :block/uuid) ["item 1" "item 2"])
        _ (outliner-property/batch-remove-property! conn block-ids :logseq.property/order-list-type)
        updated-blocks (map #(db-test/find-block-by-content @conn %) ["item 1" "item 2"])]
    (is (= [nil nil]
           (map :logseq.property/order-list-type updated-blocks))
        "Property values are batch removed")

    (is (thrown-with-msg?
         js/Error
         #"Can't remove private"
         (outliner-property/batch-remove-property! conn [(:db/id (db-test/find-page-by-title @conn "page1"))] :block/tags)))

    (is (thrown-with-msg?
         js/Error
         #"Can't remove required"
         (outliner-property/batch-remove-property! conn [(:db/id (d/entity @conn :user.class/C1))] :logseq.property.class/extends)))))

(deftest add-existing-values-to-closed-values!
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}
                :blocks [{:block/title "b1" :build/properties {:num 1}}
                         {:block/title "b2" :build/properties {:num 2}}]}])
        values (map (fn [d] (:block/uuid (d/entity @conn (:v d)))) (d/datoms @conn :avet :user.property/num))
        _ (outliner-property/add-existing-values-to-closed-values! conn :user.property/num values)]
    (is (= [1 2]
           (map db-property/closed-value-content (:block/_closed-value-property (d/entity @conn :user.property/num)))))))

(deftest upsert-closed-value!
  (let [conn (db-test/create-conn-with-blocks
              {:properties {:num {:build/closed-values [{:uuid (random-uuid) :value 2}]
                                  :logseq.property/type :number}}})]

    (testing "Add non-number choice shouldn't work"
      (is
       (thrown-with-msg?
        js/Error
        #"Can't convert"
        (outliner-property/upsert-closed-value! conn :user.property/num {:value "not a number"}))))

    (testing "Can't add existing choice"
      (is
       (thrown-with-msg?
        js/Error
        #"Closed value choice already exists"
        (outliner-property/upsert-closed-value! conn :user.property/num {:value 2}))))

    (testing "Add choice successfully"
      (let [_ (outliner-property/upsert-closed-value! conn :user.property/num {:value 3})
            b (first (d/q '[:find [(pull ?b [*]) ...] :where [?b :logseq.property/value 3]] @conn))]
        (is (ldb/closed-value? (d/entity @conn (:db/id b))))
        (is (= [2 3]
               (map db-property/closed-value-content (:block/_closed-value-property (d/entity @conn :user.property/num)))))))

    (testing "Update choice successfully"
      (let [b (first (d/q '[:find [(pull ?b [*]) ...] :where [?b :logseq.property/value 2]] @conn))
            _ (outliner-property/upsert-closed-value! conn :user.property/num {:id (:block/uuid b)
                                                                               :value 4
                                                                               :description "choice 4"})
            updated-b (d/entity @conn [:block/uuid (:block/uuid b)])]
        (is (= 4 (db-property/closed-value-content updated-b)))
        (is (= "choice 4" (db-property/property-value-content (:logseq.property/description updated-b))))))))

(deftest delete-closed-value!
  (let [closed-value-uuid (random-uuid)
        used-closed-value-uuid (random-uuid)
        conn (db-test/create-conn-with-blocks
              {:properties {:default {:build/closed-values [{:uuid closed-value-uuid :value "foo"}
                                                            {:uuid used-closed-value-uuid :value "bar"}]
                                      :logseq.property/type :default}}
               :pages-and-blocks
               [{:page {:block/title "page1"}
                 :blocks [{:block/title "b1" :user.property/default [:block/uuid used-closed-value-uuid]}]}]})
        _ (assert (:user.property/default (db-test/find-block-by-content @conn "b1")))
        property-uuid (:block/uuid (d/entity @conn :user.property-default))
        _ (outliner-property/delete-closed-value! conn property-uuid [:block/uuid closed-value-uuid])]
    (is (nil? (d/entity @conn [:block/uuid closed-value-uuid])))))

(deftest class-add-property!
  (let [conn (db-test/create-conn-with-blocks
              {:classes {:c1 {}}
               :properties {:p1 {:logseq.property/type :default}
                            :p2 {:logseq.property/type :default}}})
        _ (outliner-property/class-add-property! conn :user.class/c1 :user.property/p1)
        _ (outliner-property/class-add-property! conn :user.class/c1 :user.property/p2)]
    (is (= [:user.property/p1 :user.property/p2]
           (map :db/ident (:logseq.property.class/properties (d/entity @conn :user.class/c1)))))))

(deftest class-remove-property!
  (let [conn (db-test/create-conn-with-blocks
              {:classes {:c1 {:build/class-properties [:p1 :p2]}}})
        _ (outliner-property/class-remove-property! conn :user.class/c1 :user.property/p1)]
    (is (= [:user.property/p2]
           (map :db/ident (:logseq.property.class/properties (d/entity @conn :user.class/c1)))))))

(deftest get-block-classes-properties
  (let [conn (db-test/create-conn-with-blocks
              {:classes {:c1 {:build/class-properties [:p1]}
                         :c2 {:build/class-properties [:p2 :p3]}}
               :pages-and-blocks
               [{:page {:block/title "p1"}
                 :blocks [{:block/title "o1"
                           :build/tags [:c1 :c2]}]}]})
        block (db-test/find-block-by-content @conn "o1")]
    (is (= [:user.property/p1 :user.property/p2 :user.property/p3]
           (map :db/ident (:classes-properties (outliner-property/get-block-classes-properties @conn (:db/id block))))))))

(deftest extends-cycle
  (testing "Fail when creating a cycle of extends"
    (let [conn (db-test/create-conn-with-blocks
                {:classes {:Class1 {}
                           :Class2 {}
                           :Class3 {}}})
          db @conn
          class1 (d/entity db :user.class/Class1)
          class2 (d/entity db :user.class/Class2)
          class3 (d/entity db :user.class/Class3)]
      (outliner-property/set-block-property! conn (:db/id class1) :logseq.property.class/extends (:db/id class2))
      (outliner-property/set-block-property! conn (:db/id class2) :logseq.property.class/extends (:db/id class3))
      (is (thrown-with-msg?
           js/Error
           #"Extends cycle"
           (outliner-property/set-block-property! conn (:db/id class3) :logseq.property.class/extends (:db/id class1)))
          "Extends cycle"))))

(deftest delete-property-value!
  (let [conn (db-test/create-conn-with-blocks
              {:classes {:C1 {}
                         :C2 {}
                         :C3 {:build/class-extends [:C1 :C2]}}})]
    (outliner-property/delete-property-value! conn :user.class/C3 :logseq.property.class/extends
                                              (:db/id (d/entity @conn :user.class/C2)))
    (is (= [:user.class/C1]
           (:logseq.property.class/extends (db-test/readable-properties (d/entity @conn :user.class/C3))))
        "Specific property value is deleted")

    (outliner-property/delete-property-value! conn :user.class/C3 :logseq.property.class/extends
                                              (:db/id (d/entity @conn :user.class/C1)))
    (is (= [:logseq.class/Root]
           (:logseq.property.class/extends (db-test/readable-properties (d/entity @conn :user.class/C3))))
        "Extends property is restored back to Root")))