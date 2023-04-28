(ns flow-storm.tracer
  (:require [flow-storm.utils :as utils]
            [hansel.instrument.runtime :refer [*runtime-ctx*]]
            [flow-storm.runtime.values :refer [snapshot-reference]]
            [flow-storm.runtime.indexes.api :as indexes-api]
            [flow-storm.runtime.types.fn-call-trace :refer [make-fn-call-trace]]
            [flow-storm.runtime.types.fn-return-trace :refer [make-fn-return-trace]]
            [flow-storm.runtime.types.expr-trace :refer [make-expr-trace]]
            [flow-storm.runtime.types.bind-trace :refer [make-bind-trace]]))

(declare start-tracer)
(declare stop-tracer)

(def recording (atom true))
(def breakpoints (atom #{}))

(defn set-recording [x]
  (if x
    (do
      (reset! recording true)
      (indexes-api/reset-all-threads-trees-build-stack nil))

    (reset! recording false)))

(defn recording? []
  @recording)

#?(:clj
   (defn- block-this-thread [flow-id]
     (let [thread-obj (Thread/currentThread)
           tname (.getName thread-obj)
           tid (.getId thread-obj)]
       (if (= tname"JavaFX Application Thread")

         (utils/log "WARNING, skipping thread block, trace is being executed by the UI thread and doing so will freeze the UI.")

         (do
           (utils/log (format "Blocking thread %d %s" tid tname))
           (indexes-api/mark-thread-blocked flow-id tid)
           (locking thread-obj
             (.wait thread-obj))
           (indexes-api/mark-thread-unblocked flow-id tid)
           (utils/log (format "Thread %d %s unlocked, continuing ..." tid tname)))))))

#?(:clj
   (defn unblock-thread [thread-id]
     (let [thread-obj (utils/get-thread-object-by-id thread-id)]       
       (locking thread-obj
         (.notifyAll thread-obj)))))

(defn add-breakpoint! [fn-ns fn-name args-pred]
  (swap! breakpoints conj (with-meta [fn-ns fn-name] {:predicate args-pred})))

(defn remove-breakpoint! [fn-ns fn-name]
  (swap! breakpoints disj [fn-ns fn-name]))

(defn clear-breakpoints! []
  (reset! breakpoints nil))

(defn all-breakpoints []
  @breakpoints)

(defn trace-flow-init-trace

  "Send flow initialization trace"
  
  [{:keys [flow-id form-ns form]}]
  (when @recording
    (let [trace {:trace/type :flow-init
                 :flow-id flow-id
                 :ns form-ns
                 :form form
                 :timestamp (utils/get-monotonic-timestamp)}]
      (indexes-api/add-flow-init-trace trace))))

(defn trace-form-init

  "Send form initialization trace only once for each thread."
  
  [{:keys [form-id ns def-kind dispatch-val form]}]

  (when @recording  
    (let [{:keys [flow-id]} *runtime-ctx*
          thread-id (utils/get-current-thread-id)]
      (when-not (indexes-api/get-form flow-id thread-id form-id)
        (let [trace {:trace/type :form-init
                     :flow-id flow-id
                     :form-id form-id
                     :thread-id thread-id
                     :thread-name (utils/get-current-thread-name)
                     :form form
                     :ns ns
                     :def-kind def-kind
                     :mm-dispatch-val dispatch-val
                     :timestamp (utils/get-monotonic-timestamp)}]

          (indexes-api/add-form-init-trace trace))))))

(defn trace-fn-call

  "Send function call traces"
  
  ([{:keys [form-id ns fn-name fn-args]}] ;; for using with hansel
   
   (let [{:keys [flow-id]} *runtime-ctx*]
     (trace-fn-call flow-id ns fn-name fn-args form-id)))
  
  ([flow-id fn-ns fn-name fn-args form-id]  ;; for using with storm
   
   (when @recording
     #?(:clj
        (let [brks @breakpoints]
          (when (and (pos? (count brks))
                     (contains? brks [fn-ns fn-name])
                     (apply (-> (get brks [fn-ns fn-name]) meta :predicate) fn-args)
                     ;; this is kind of HACKY but do not block if the flow don't exist yet
                     ;; because the only UI to unblock threads is inside the flow tab 
                     (indexes-api/flow-exists? flow-id))
            (block-this-thread flow-id))))
     
     (let [timestamp (utils/get-monotonic-timestamp)
           thread-id (utils/get-current-thread-id)
           thread-name (utils/get-current-thread-name)
           args (mapv snapshot-reference fn-args)]
       (indexes-api/add-fn-call-trace
        flow-id
        thread-id
        thread-name
        (make-fn-call-trace fn-ns fn-name form-id timestamp args))))))

(defn trace-fn-return

  "Send function return traces"
  
  ([{:keys [return coor form-id]}]  ;; for using with hansel
   
   (let [{:keys [flow-id]} *runtime-ctx*]
     (trace-fn-return flow-id return coor form-id)
     return))
  
  ([flow-id return coord form-id] ;; for using with storm
   (when @recording
     (let [timestamp (utils/get-monotonic-timestamp)
           thread-id (utils/get-current-thread-id)]
       (indexes-api/add-fn-return-trace
        flow-id
        thread-id      
        (make-fn-return-trace form-id timestamp coord (snapshot-reference return)))))))

(defn trace-expr-exec
  
  "Send expression execution trace."
  
  ([{:keys [result coor form-id]}]  ;; for using with hansel   
   (let [{:keys [flow-id]} *runtime-ctx*]
     (trace-expr-exec flow-id result coor form-id))
   
   result)

  ([flow-id result coord form-id]  ;; for using with storm
   (when @recording
     (let [timestamp (utils/get-monotonic-timestamp)
           thread-id (utils/get-current-thread-id)]
       (indexes-api/add-expr-exec-trace      
        flow-id
        thread-id      
        (make-expr-trace form-id timestamp coord (snapshot-reference result)))))))

(defn trace-bind
  
  "Send bind trace."
  
  ([{:keys [symb val coor]}] ;; for using with hansel

   (let [{:keys [flow-id]} *runtime-ctx*]
     (trace-bind flow-id coor (name symb) (snapshot-reference val))))

  ([flow-id coord sym-name val]  ;; for using with storm
   (when @recording
     (let [timestamp (utils/get-monotonic-timestamp)
           thread-id (utils/get-current-thread-id)]
       (indexes-api/add-bind-trace      
        flow-id
        thread-id      
        (make-bind-trace timestamp sym-name val coord))))))

(defn hansel-config

  "Builds a hansel config from inst-opts"
  
  [{:keys [disable] :or {disable #{}}}]
  (cond-> `{:trace-form-init trace-form-init
            :trace-fn-call trace-fn-call
            :trace-fn-return trace-fn-return
            :trace-expr-exec trace-expr-exec
            :trace-bind trace-bind}
    
    (disable :expr-exec)    (dissoc :trace-expr-exec)
    (disable :bind)         (dissoc :trace-expr-exec)
    (disable :anonymous-fn) (assoc :disable #{:anonymous-fn})))
