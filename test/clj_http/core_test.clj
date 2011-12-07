(ns clj-http.core-test
  (:use [clojure.test]
        [clojure.java.io :only [file]])
  (:require [clojure.pprint :as pp]
            [clj-http.core :as core]
            [clj-http.util :as util]
            [ring.adapter.jetty :as ring])
  (:import (java.io ByteArrayInputStream)))

(defn handler [req]
  ;;(pp/pprint req)
  ;;(println) (println)
  (condp = [(:request-method req) (:uri req)]
    [:get "/get"]
    {:status 200 :body "get"}
    [:head "/head"]
    {:status 200}
    [:get "/content-type"]
    {:status 200 :body (:content-type req)}
    [:get "/header"]
    {:status 200 :body (get-in req [:headers "x-my-header"])}
    [:post "/post"]
    {:status 200 :body (slurp (:body req))}
    [:get "/error"]
    {:status 500 :body "o noes"}
    [:get "/timeout"]
    (do
      (Thread/sleep 10)
      {:status 200 :body "timeout"})
    [:delete "/delete-with-body"]
    {:status 200 :body "delete-with-body"}
    [:post "/multipart"]
    {:status 200 :body (:body req)}))

(defn run-server
  []
  (defonce server
    (future (ring/run-jetty handler {:port 18080}))))

(def base-req
  {:scheme "http"
   :server-name "localhost"
   :server-port 18080})

(defn request [req]
  (core/request (merge base-req req)))

(defn slurp-body [req]
  (slurp (:body req)))

(deftest ^{:integration true} makes-get-request
  (run-server)
  (let [resp (request {:request-method :get :uri "/get"})]
    (is (= 200 (:status resp)))
    (is (= "get" (slurp-body resp)))))

(deftest ^{:integration true} makes-head-request
  (run-server)
  (let [resp (request {:request-method :head :uri "/head"})]
    (is (= 200 (:status resp)))
    (is (nil? (:body resp)))))

(deftest ^{:integration true} sets-content-type-with-charset
  (run-server)
  (let [resp (request {:request-method :get :uri "/content-type"
                       :content-type "text/plain" :character-encoding "UTF-8"})]
    (is (= "text/plain; charset=UTF-8" (slurp-body resp)))))

(deftest ^{:integration true} sets-content-type-without-charset
  (run-server)
  (let [resp (request {:request-method :get :uri "/content-type"
                       :content-type "text/plain"})]
    (is (= "text/plain" (slurp-body resp)))))

(deftest ^{:integration true} sets-arbitrary-headers
  (run-server)
  (let [resp (request {:request-method :get :uri "/header"
                       :headers {"X-My-Header" "header-val"}})]
    (is (= "header-val" (slurp-body resp)))))

(deftest ^{:integration true} sends-and-returns-byte-array-body
  (run-server)
  (let [resp (request {:request-method :post :uri "/post"
                       :body (util/utf8-bytes "contents")})]
    (is (= 200 (:status resp)))
    (is (= "contents" (slurp-body resp)))))

(deftest ^{:integration true} returns-arbitrary-headers
  (run-server)
  (let [resp (request {:request-method :get :uri "/get"})]
    (is (string? (get-in resp [:headers "date"])))))

(deftest ^{:integration true} returns-status-on-exceptional-responses
  (run-server)
  (let [resp (request {:request-method :get :uri "/error"})]
    (is (= 500 (:status resp)))))

(deftest ^{:integration true} sets-socket-timeout
  (run-server)
  (try
    (request {:request-method :get :uri "/timeout" :socket-timeout 1})
    (throw (Exception. "Shouldn't get here."))
    (catch Exception e
      (is (= java.net.SocketTimeoutException (class e))))))

(deftest ^{:integration true} delete-with-body
  (run-server)
  (let [resp (request {:request-method :delete :uri "/delete-with-body"
                       :body (.getBytes "foo bar")})]
    (is (= 200 (:status resp)))))

(deftest ^{:integration true} self-signed-ssl-get
  (let [t (doto (Thread. #(ring/run-jetty handler
                                          {:port 8081 :ssl-port 18082 :ssl? true
                                           :keystore "test-resources/keystore"
                                           :key-password "keykey"})) .start)]
    (try
      (is (thrown? javax.net.ssl.SSLPeerUnverifiedException
                   (request {:request-method :get :uri "/get"
                             :server-port 18082 :scheme "https"})))
      (let [resp (request {:request-method :get :uri "/get" :server-port 18082
                           :scheme "https" :insecure? true})]
        (is (= 200 (:status resp)))
        (is (= "get" (slurp-body resp))))
      (finally
       (.stop t)))))

(deftest ^{:integration true} multipart-form-uploads
  (run-server)
  (let [bytes (util/utf8-bytes "byte-test")
        stream (ByteArrayInputStream. bytes)
        resp (request {:request-method :post :uri "/multipart"
                       :multipart [["a" "testFINDMEtest"]
                                   ["b" bytes]
                                   ["c" stream]
                                   ["d" (file "test-resources/keystore")]]})
        resp-body (apply str (map #(try (char %) (catch Exception _ ""))
                                  (:body resp)))]
    (is (= 200 (:status resp)))
    (is (re-find #"testFINDMEtest" resp-body))
    (is (re-find #"byte-test" resp-body))
    (is (re-find #"name=\"c\"" resp-body))
    (is (re-find #"name=\"d\"" resp-body))))

(deftest ^{:integration true} t-save-request-obj
  (run-server)
  (let [resp (request {:request-method :post :uri "/post"
                       :body (.getBytes "foo bar")
                       :save-request? true})]
    (is (= 200 (:status resp)))
    (is (= {:scheme "http"
            :http-url "http://localhost:18080/post"
            :request-method :post
            :save-request? true
            :uri "/post"
            :server-name "localhost"
            :server-port 18080}
           (dissoc (:request resp) :body)))))
