(ns redis.internal
  (:refer-clojure :exclude [send read read-line])
  (:use [redis.utils])
  (:import [java.io InputStream BufferedInputStream]
           [java.net Socket]
           [org.apache.commons.pool.impl GenericObjectPool]
           [org.apache.commons.pool BasePoolableObjectFactory]))

(set! *warn-on-reflection* true)

(def *cr*  0x0d)
(def *lf*  0x0a)
(defn- cr? [c] (= c *cr*))
(defn- lf? [c] (= c *lf*))

(defstruct connection
  :host :port :password :db :timeout :socket :reader :writer)

(def *connection* (struct-map connection
                    :host     "127.0.0.1"
                    :port     6379
                    :password nil
                    :db       0
                    :timeout  5000
                    :socket   nil
                    :reader   nil
                    :writer   nil))

(defn socket* []
  (or (:socket *connection*)
      (throw (Exception. "Not connected to a Redis server"))))

(defn send-command
  "Send a command string to server"
  [#^String cmd]
  (prn cmd)
  (let [out (.getOutputStream (#^Socket socket*))
        bytes (.getBytes cmd "UTF-8")]
    (.write out bytes)))

(defn- uppercase [#^String s] (.toUpperCase s))
(defn- trim [#^String s] (.trim s))
(defn- parse-int [#^String s] (Integer/parseInt s))
;; (defn- char-array [len] (make-array Character/TYPE len))

(defn read-crlf
  "Read a CR+LF combination from Reader"
  [#^InputStream reader]
  (let [cr (.read reader)
        lf (.read reader)]
    (when-not
        (and (cr? cr)
             (lf? lf))
      (throw (Exception. "Error reading CR/LF")))
    nil))

(defn read-line-crlf
  "Read from reader until exactly a CR+LF combination is
  found. Returns the line read without trailing CR+LF.

  This is used instead of Reader.readLine() method since that method
  tries to read either a CR, a LF or a CR+LF, which we don't want in
  this case."
  [#^BufferedInputStream reader]
  (loop [line []
         c (.read reader)]
    (when (< c 0)
      (throw (Exception. "Error reading line: EOF reached before CR/LF sequence")))
    (if (cr? c)
      (let [next (.read reader)]
        (if (lf? next)
          (apply str line)
          (throw (Exception. "Error reading line: Missing LF"))))
      (recur (conj line (char c))
             (.read reader)))))

;;
;; Reply dispatching
;;
(defn- do-read [#^InputStream reader #^chars cbuf offset length]
  (let [nread (.read reader cbuf offset length)]
    (if (not= nread length)
      (recur reader cbuf (+ offset nread) (- length nread)))))

(defn reply-type
  ([#^BufferedInputStream reader]
     (char (.read reader))))

(defmulti parse-reply reply-type :default :unknown)

(defn read-reply
  ([]
     (let [reader (*connection* :reader)]
       (read-reply reader)))
  ([#^BufferedInputStream reader]
     (parse-reply reader)))

(defmethod parse-reply :unknown
  [#^BufferedInputStream reader]
  (throw (Exception. (str "Unknown reply type:"))))

(defmethod parse-reply \-
  [#^BufferedInputStream reader]
  (let [error (read-line-crlf reader)]
    (throw (Exception. (str "Server error: " error)))))

(defmethod parse-reply \+
  [#^BufferedInputStream reader]
  (read-line-crlf reader))

(defmethod parse-reply \$
  [#^BufferedInputStream reader]
  (let [line (read-line-crlf reader)
        length (parse-int line)]
    (if (< length 0)
      nil
      (let [#^bytes cbuf (byte-array length)]
        (do
          (do-read reader cbuf 0 length)
          (read-crlf reader) ;; CRLF
          (String. (.getBytes (String. cbuf)) "UTF-8"))))))

(defmethod parse-reply \*
  [#^BufferedInputStream reader]
  (let [line (read-line-crlf reader)
        count (parse-int line)]
    (if (< count 0)
      nil
      (loop [i count
             replies []]
        (if (zero? i)
          replies
          (recur (dec i) (conj replies (read-reply reader))))))))

(defmethod parse-reply \:
  [#^BufferedInputStream reader]
  (let [line (trim (read-line-crlf reader))
        int (parse-int line)]
    int))

;;
;; Command functions
;;
(defn- str-join
  "Join elements in sequence with separator"
  [separator sequence]
  (apply str (interpose separator sequence)))

(def redis-keywords #{:by :limit :get :store :alpha :desc :withscores :weights})

(defn- convert-arguments
  "Convert keyword arguments to redis keywords.  If its a keyword it is checked
against the known keywords and uppercased.  If its not a keyword it passes through."
  [x]
  (if (keyword? x)
    (if (redis-keywords x)
      (uppercase (name x))
      (throw (Exception.
	      (str "Error parsing arguments: Unknown argument: " type))))
    x))

(defn inline-command
  "Create a string for an inline command"
  [name & args]
  (let [cmd (str-join " " (conj (map convert-arguments args) name))]
    (str cmd "\r\n")))

(defn bulk-command
  "Create a string for a bulk command"
  [name & args]
  (let [data (str (last args))
        data-length (count (.getBytes data "UTF-8"))
        args* (concat (butlast args) [data-length])
        cmd (apply inline-command name args*)]
    (str cmd data "\r\n")))

(def command-fns {:inline 'inline-command
                  :bulk   'bulk-command})

(defn parse-params
  "Return a restructuring of params, which is of form:
     [arg* (& more)?]
  into
     [(arg1 arg2 ..) more]"
  [params]
  (let [[args rest] (split-with #(not= % '&) params)]
    [args (last rest)]))

(def end-subscribe (atom false))

(def test-sync =)

(defn subscribe-command [channel callback]
  (send-command (inline-command "SUBSCRIBE" channel))
  ;; skip the command reply
  (read-reply)
  (try
    (while (not @end-subscribe)
      (let [[_ _ chan-name] (read-reply)]
        (if (test-sync channel chan-name)
          (let [[_ _ key] (read-reply)
                [_ _ val] (read-reply)]
            (callback key val))
          (throw (IllegalStateException.
                  (str channel ": Subscribed channel out of sync." chan-name))))))
    (finally
       (send-command (inline-command "UNSUBSCRIBE" channel)))))

(defmacro defcommand
  "Define a function for Redis command name with parameters
  params. Type is one of :inline, :bulk or :sort, which determines how
  the command string is constructued."
  ([name params type] `(defcommand ~name ~params ~type (fn [reply#] reply#)))
  ([name params type reply-fn] `(~name ~params ~type ~reply-fn)
     (do
       (let [command (uppercase (str name))
             command-fn (type command-fns)
             [command-params
              command-params-rest] (parse-params params)]
         `(defn ~name
            ~params
            ~(if (= command "SUBSCRIBE")
               `(subscribe-command ~@params)
               `(let [request# (apply ~command-fn
                                      ~command
                                      ~@command-params
                                      ~command-params-rest)]
                  (send-command request#)
                  (~reply-fn (read-reply)))))))))

(defmacro defcommands
  [& command-defs]
  `(do ~@(map (fn [command-def]
                `(defcommand ~@command-def)) command-defs)))

;;
;; connection pooling
;;
(def *pool* (atom nil))
(def *MAX-POOL-SIZE* 330)
(def *POOL-EVICTION-RUN-EVERY-MILLIS* 30000)

(defn connect-to-server
  "Create a Socket connected to server"
  [server]
  (let [{:keys [host port timeout]} server
        socket (Socket. #^String host #^Integer port)]
    (doto socket
      (.setTcpNoDelay true)
      (.setKeepAlive true))))

(defn new-redis-connection [server-spec]
  (let [connection (merge *connection* server-spec)
        #^Socket socket (connect-to-server connection)
        input-stream (.getInputStream socket)
        output-stream (.getOutputStream socket)
        reader (BufferedInputStream. input-stream)]
    (assoc connection
      :socket socket
      :reader reader
      :created-at (System/currentTimeMillis))))

(defn connection-valid? []
  (try
   (= "PONG" (do (send-command (inline-command "PING"))
                 (read-reply)))
   (catch Exception e
     false)))

(defn connection-factory [server-spec]
  (proxy [BasePoolableObjectFactory] []
    (makeObject []
                (new-redis-connection server-spec))
    (validateObject [c]
                    (binding [*connection* c]
                      (connection-valid?)))
    (destroyObject [c]
                   (try
                    (.close #^Socket (:socket c))
                    (catch Exception e
                      ;; Guard against broken pipe exception when redis
                      ;; connection times out.
                      )))))

(defrunonce init-pool [server-spec]
  (let [factory (connection-factory server-spec)
        p (doto (GenericObjectPool. factory)
            (.setMaxActive *MAX-POOL-SIZE*)
            (.setLifo false)
            (.setTimeBetweenEvictionRunsMillis *POOL-EVICTION-RUN-EVERY-MILLIS*)
            (.setWhenExhaustedAction GenericObjectPool/WHEN_EXHAUSTED_BLOCK)
            (.setTestWhileIdle true)
            (.setTestOnBorrow true))]
    (reset! *pool* p)))

(defn pool-status []
  [(.getNumActive #^GenericObjectPool @*pool*) (.getNumIdle #^GenericObjectPool @*pool*) (.getMaxActive #^GenericObjectPool @*pool*)])

(defn get-connection-from-pool [server-spec]
  (init-pool server-spec)
  (.borrowObject #^GenericObjectPool @*pool*))

(defn return-connection-to-pool [c]
  (.returnObject #^GenericObjectPool @*pool* c))

(defn with-server* [server-spec func]
  (binding [*connection* (get-connection-from-pool server-spec)]
    (try
     (func)
     (finally
      (return-connection-to-pool *connection*)))))
