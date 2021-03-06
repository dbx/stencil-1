(ns stencil.cleanup
  "

  This namespace annotates and normalizes Control AST.

  data flow is the following:

  valid XML String -> tokens -> Annotated Control AST -> Normalized Control AST -> Evaled AST -> Hiccup or valid XML String
  "
  (:require [stencil [util :refer :all] [types :refer :all]]))

(set! *warn-on-reflection* true)

(declare control-ast-normalize)

(defn- tokens->ast-step [[queue & ss0 :as stack] token]
  (case (:cmd token)
    (:if :for) (conj (mod-stack-top-conj stack token) [])

    :else      (conj (mod-stack-top-last ss0 update :blocks (fnil conj []) {:children queue}) [])

    :end       (mod-stack-top-last ss0 update :blocks conj {:children queue})

    (:echo nil)        (mod-stack-top-conj stack token)))

(defn tokens->ast
  "Flat token listabol nested AST-t csinal (listak listai)"
  [tokens]
  (let [result (reduce tokens->ast-step '([]) tokens)]
    (assert (= 1 (count result)))
    (peek result)))

(defn nested-tokens-fmap-postwalk
  "Melysegi bejaras egy XML fan.

  https://en.wikipedia.org/wiki/Depth-first_search"
  [f-cmd-block-before f-cmd-block-after f-child nested-tokens]
  (let [update-children
        #(update % :children
                 (partial nested-tokens-fmap-postwalk
                          f-cmd-block-before f-cmd-block-after
                          f-child))]
    (vec
     (for [token nested-tokens]
       (if (:cmd token)
         (as-> token token
           (update token :blocks
                   (partial mapv
                            (comp (partial f-cmd-block-after token)
                                  update-children
                                  (partial f-cmd-block-before token)))))
         (f-child token))))))

(defn annotate-environments
  "Vegigmegy minden tokenen es a parancs blokkok :before es :after kulcsaiba
   beleteszi az adott token kornyezetet."
  [control-ast]
  (let [stack (volatile! ())]
    (nested-tokens-fmap-postwalk
     (fn before-cmd-block [_ block]
       (assoc block :before @stack))

     (fn after-cmd-block [_ block]
       (let [stack-before (:before block)
             [a b]        (stacks-difference-key :open stack-before @stack)]
         (assoc block :before a :after b)))

     (fn child [item]
       (cond
         (:open item)
         (vswap! stack conj item)

         (:close item)
         (if (= (:close item) (:open (first @stack)))
           (vswap! stack next)
           (throw (ex-info "Unexpected stack state" {:stack @stack, :item item}))))
       item)
     control-ast)))

(defn stack-revert-close
  "Megfordítja a listát es az :open elemeket :close elemekre kicseréli."
  [stack] (reduce (fn [stack item] (if (:open item) (conj stack (->CloseTag (:open item))) stack)) () stack))

;; egy {:cmd ...} parancs objektumot kibont:
;; a :blocks kulcs alatt levo elemeket normalizalja es specialis kulcsok alatt elhelyezi
;; igy amikor vegrehajtjuk a parancs objektumot, akkor az eredmeny is
;; valid fa lesz, tehat a nyito-bezaro tagek helyesen fognak elhelyezkedni.
(defmulti control-ast-normalize-step :cmd)

;; Itt nincsen blokk, amit normalizálni kellene
(defmethod control-ast-normalize-step :echo [echo-command] echo-command)
(defmethod control-ast-normalize-step :include [echo-command] echo-command)

;; A feltételes elágazásoknál mindig generálunk egy javított THEN ágat
(defmethod control-ast-normalize-step :if [control-ast]
  (case (count (:blocks control-ast))
    2 (let [[then else] (:blocks control-ast)
            then2 (concat (map control-ast-normalize (:children then))
                          (stack-revert-close (:before else))
                          (:after else))
            else2 (concat (stack-revert-close (:before then))
                          (:after then)
                          (map control-ast-normalize (:children else)))]
        (-> (dissoc control-ast :blocks)
            (assoc :then then2, :else else2)))

    1 (let [[then] (:blocks control-ast)
            else   (:after then)]
        (-> (dissoc control-ast :blocks)
            (assoc :then (map control-ast-normalize (:children then)), :else else)))))

;; Egy ciklusnal kulon kell valasztani a kovetkezo eseteket:
;; - body-run-none: a body resz egyszer sem fut le, mert a lista nulla elemu.
;; - body-run-once: a body resz eloszor fut le, ha a lista legalabb egy elemu
;; - body-run-next: a body resz masodik, harmadik, stb. beillesztese, haa lista legalabb 2 elemu.
;; Ezekbol az esetekbol kell futtataskor a megfelelo(ket) kivalasztani es behelyettesiteni.
(defmethod control-ast-normalize-step :for [control-ast]
  (assert (= 1 (count (:blocks control-ast)))
          "Egy ciklusnak csak egy body resze lehet!")
  (let [[{:keys [children before after]}] (:blocks control-ast)
        children (mapv control-ast-normalize children)]
    (-> control-ast
        (dissoc :blocks)
        (assoc :body-run-none (vec (concat (stack-revert-close before) after))
               :body-run-once (vec children)
               :body-run-next (vec (concat (stack-revert-close after) before children))))))

(defn control-ast-normalize
  "Mélységi bejárással rekurzívan normalizálja az XML fát."
  [control-ast]
  (cond
    (vector? control-ast) (mapv control-ast-normalize control-ast)
    (:text control-ast)   control-ast
    (:open control-ast)   control-ast
    (:close control-ast)  control-ast
    (:cmd control-ast)    (control-ast-normalize-step control-ast)
    (:open+close control-ast) control-ast
    :else                 (throw (ex-info (str "Unexpected object: " (type control-ast)) {:ast control-ast}))))

(defn find-variables [control-ast]
  ;; meg a normalizalas lepes elott
  ;; amikor van benne blocks
  ;; mapping: {Sym -> Str}
  (letfn [(resolve-sym [mapping s]
            (assert (map? mapping))
            (assert (symbol? s))
            ;; megprobal egy adott szimbolumot a mapping alapjan rezolvalni.
            ;; visszaad egy stringet
            (if (.contains (name s) ".")
              (let [[p1 p2] (vec (.split (name s) "\\." 2))]
                (if-let [pt (mapping (symbol p1))]
                  (str pt "." p2)
                  (name s)))
              (mapping s (name s))))
          (expr [mapping rpn]
                (assert (sequential? rpn)) ;; RPN kifejezes kell legyen
                (keep (partial resolve-sym mapping) (filter symbol? rpn)))
          ;; iff rpn expr consists of 1 variable only -> resolves that one variable.
          (maybe-variable [mapping rpn]
                          (when (and (= 1 (count rpn)) (symbol? (first rpn)))
                            (resolve-sym mapping (first rpn))))
          (collect [m xs] (mapcat (partial collect-1 m) xs))
          (collect-1 [mapping x]
                     (case (:cmd x)
                       :echo (expr mapping (:expression x))

                       :if   (concat (expr mapping (:condition x))
                                     (collect mapping (apply concat (:blocks x))))

                       :for  (let [variable (maybe-variable mapping (:expression x))
                                   exprs    (expr mapping (:expression x))
                                   mapping  (if variable
                                              (assoc mapping (:variable x) (str variable "[]"))
                                              mapping)]
                               (concat exprs (collect mapping (apply concat (:blocks x)))))
                       []))]
    (distinct (collect {} control-ast))))

; (find-variables [])

(defn process [raw-token-seq]
  (let [ast (tokens->ast raw-token-seq)
        executable (control-ast-normalize (annotate-environments ast))]
    {:variables  (find-variables ast)
     :dynamic?   (boolean (some :cmd executable))
     :executable executable}))

:OK
