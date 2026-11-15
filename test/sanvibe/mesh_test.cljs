(ns sanvibe.mesh-test
  "Known answers from RFC 4493 and the sample data of the Mesh Profile spec
  (section 8), plus round trips through the layers we only have our own data
  for."
  (:require [cljs.test :refer [deftest is testing]]
            [sanvibe.mesh :as mesh]))

(def ^:private hex->buf mesh/hex->buf)
(def ^:private buf->hex mesh/buf->hex)

(deftest cmac-rfc-4493
  (let [k (hex->buf "2b7e151628aed2a6abf7158809cf4f3c")
        m (hex->buf (str "6bc1bee22e409f96e93d7e117393172a"
                         "ae2d8a571e03ac9c9eb76fac45af8e51"
                         "30c81c46a35ce411e5fbc1191a0a52ef"
                         "f69f2445df4f9b17ad2b417be66c3710"))]
    (testing "empty, partial and complete last blocks"
      (is (= "bb1d6929e95937287fa37d129b756746" (buf->hex (mesh/cmac k (.subarray m 0 0)))))
      (is (= "070a16b46b4d4144f79bdd9dd04a287c" (buf->hex (mesh/cmac k (.subarray m 0 16)))))
      (is (= "dfa66747de9ae63030ca32611497c827" (buf->hex (mesh/cmac k (.subarray m 0 40)))))
      (is (= "51f0bebf7e3b9d92fc49741779363cfe" (buf->hex (mesh/cmac k m)))))))

(deftest key-derivation-functions
  (is (= "b73cefbd641ef2ea598c2b6efb62f79c"
         (buf->hex (mesh/s1 (js/Buffer.from "test" "ascii")))))
  (is (= "f6ed15a8934afbe7d83e8dcb57fcf5d7"
         (buf->hex (mesh/k1 (hex->buf "3216d1509884b533248541792b877f98")
                            (hex->buf "2ba14ffa0df84a2831938d57d276cab4")
                            (hex->buf "5a09d60797eeb4478aada59db3352a0d")))))
  (let [{:keys [nid enc-key privacy-key]} (mesh/k2 (hex->buf "f7a2a44f8e8a8029064f173ddc1e2b00")
                                                   (hex->buf "00"))]
    (is (= 0x7f nid))
    (is (= "9f589181a0f50de73c8070c7a6d27f46" (buf->hex enc-key)))
    (is (= "4c715bd4a64b938f99b453351653124f" (buf->hex privacy-key))))
  (is (= "ff046958233db014"
         (buf->hex (mesh/k3 (hex->buf "f7a2a44f8e8a8029064f173ddc1e2b00")))))
  (is (= 0x38 (mesh/k4 (hex->buf "3216d1509884b533248541792b877f98")))))

(def ^:private sample-net-key (mesh/net-key (hex->buf "7dd7364cd842ad18c17c2b820c84c3d6")))
(def ^:private sample-app-key (mesh/app-key (hex->buf "63964771734fbd76e3b40519d1d94a48")))

(deftest derived-keys
  (is (= 0x68 (:nid sample-net-key)))
  (is (= "0953fa93e7caac9638f58820220a398e" (buf->hex (:enc-key sample-net-key))))
  (is (= "8b84eedec100067d670971dd2aa700cf" (buf->hex (:privacy-key sample-net-key))))
  (is (= "3ecaff672f673370" (buf->hex (:network-id sample-net-key))))
  (is (= "5423d967da639a99cb02231a83f7d254" (buf->hex (:beacon-key sample-net-key))))
  (is (= 0x26 (:aid sample-app-key))))

(deftest network-layer
  (let [meta {:iv-index 0x12345678 :ctl 1 :ttl 0 :seq 1 :src 0x1201 :dst 0xfffd}
        transport-pdu (hex->buf "034b50057e400000010000")
        expected "68eca487516765b5e5bfdacbaf6cb7fb6bff871f035444ce83a670df"]
    (testing "spec message #1"
      (is (= expected (buf->hex (mesh/encode-network sample-net-key meta transport-pdu)))))
    (testing "decoding it again"
      (let [decoded (mesh/decode-network sample-net-key 0x12345678 (hex->buf expected))]
        (is (= (dissoc meta :proxy?) (dissoc decoded :transport-pdu)))
        (is (= (buf->hex transport-pdu) (buf->hex (:transport-pdu decoded))))))
    (testing "other networks' PDUs"
      (is (nil? (mesh/decode-network (mesh/net-key (hex->buf "00112233445566778899aabbccddeeff"))
                                     0x12345678 (hex->buf expected)))))))

(deftest access-messages
  (let [meta {:iv-index 0x12345678 :seq 0x07080b :src 0x1234 :dst 0x0003}
        payload (hex->buf "c00b8a0a0102")]
    (testing "unsegmented"
      (let [pdu (mesh/encode-access sample-app-key meta payload)
            {:keys [upper]} (mesh/parse-lower pdu)]
        (is (= (bit-or 0x40 0x26) (aget pdu 0)))
        (is (= (buf->hex payload) (buf->hex (mesh/decrypt-access sample-app-key meta upper))))
        (is (nil? (mesh/decrypt-access (mesh/app-key (hex->buf "00112233445566778899aabbccddeeff"))
                                       meta upper)))))
    (testing "too long for one segment"
      (is (thrown? js/Error (mesh/encode-access sample-app-key meta (js/Buffer.alloc 12)))))
    (testing "segmented, reassembled"
      (let [long-payload (js/Buffer.from (apply str (repeat 30 "x")) "ascii")
            segments (mesh/encode-access-segments sample-app-key meta long-payload)
            parsed (map mesh/parse-lower segments)
            seq-auth (mesh/seq-auth (+ (:seq meta) 2) (:seq-zero (first parsed)))]
        (is (= 3 (count segments)))
        (is (= [0 1 2] (map :seg-o parsed)))
        (is (every? #(= 2 (:seg-n %)) parsed))
        (is (= (:seq meta) seq-auth))
        (is (= (buf->hex long-payload)
               (buf->hex (mesh/decrypt-access sample-app-key
                                              (assoc meta :seq seq-auth :szmic 0)
                                              (apply mesh/bcat (map :segment parsed))))))))))

(deftest segment-acks
  (is (= {:seq-zero 0x0abc :block-ack 0x5}
         (mesh/parse-segment-ack (mesh/segment-ack 0x0abc [0 2])))))

(deftest access-opcodes
  (is (= {:opcode 0x04 :params "aa"}
         (update (mesh/parse-access (hex->buf "04aa")) :params buf->hex)))
  (is (= {:opcode 0x8201 :params ""}
         (update (mesh/parse-access (hex->buf "8201")) :params buf->hex)))
  (is (= {:opcode 0xc08b0a :params "50"}
         (update (mesh/parse-access (hex->buf "c08b0a50")) :params buf->hex))))

(deftest proxy-pdus
  (let [pdu (js/Buffer.from (apply str (repeat 50 "y")) "ascii")
        reassemble (fn [packets]
                     (reduce (fn [acc packet]
                               (let [[acc' message] (mesh/proxy-reassemble acc packet)]
                                 (or message acc')))
                             nil packets))]
    (testing "fits in one"
      (let [packets (mesh/proxy-segments :network (.subarray pdu 0 19) 20)]
        (is (= 1 (count packets)))
        (is (= 0x00 (aget (first packets) 0)))))
    (testing "split and reassembled"
      (let [packets (mesh/proxy-segments :proxy-config pdu 20)
            {:keys [type] reassembled :pdu} (reassemble packets)]
        (is (= [0x42 0x82 0xc2] (map #(aget % 0) packets)))
        (is (= :proxy-config type))
        (is (= (buf->hex pdu) (buf->hex reassembled)))))))
