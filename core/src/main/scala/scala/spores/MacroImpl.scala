/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2002-2013, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

package scala.spores

import scala.reflect.macros.Context


private[spores] class MacroImpl[C <: Context with Singleton](val c: C) {
  import c.universe._

  def ownerChainContains(sym: Symbol, owner: Symbol): Boolean =
    sym != null && (sym.owner == owner || {
      sym.owner != NoSymbol && ownerChainContains(sym.owner, owner)
    })

  def conforms(funTree: c.Tree): (List[Symbol], Type, Tree, List[Symbol]) = {
    // traverse body of `fun` and check that the free vars access only allowed things
    // `validEnv` == symbols declared in the spore header
    val (validEnv, funLiteral) = funTree match {
      case Block(stmts, expr) =>
        val validVarSyms = stmts.toList flatMap {
          case vd @ ValDef(mods, name, tpt, rhs) =>
            List(vd.symbol)
          case stmt =>
            c.error(stmt.pos, "Only val defs allowed at this position")
            List()
        }
        validVarSyms foreach { sym => debug("valid: " + sym) }
        (validVarSyms, expr)

      case expr =>
        (List(), expr)
    }

    val captureSym = typeOf[spores.`package`.type].member(newTermName("capture"))

    funLiteral match {
      case fun @ Function(vparams, body) =>

        // contains all symbols found in `capture` syntax
        var capturedSyms = List[Symbol]()

        // is the use of symbol s allowed via spore rules? (in the spore body)
        def isSymbolValid(s: Symbol): Boolean =
          validEnv.contains(s) ||              // is `s` declared in the spore header?
          capturedSyms.contains(s) ||          // is `s` captured using the `capture` syntax?
          ownerChainContains(s, fun.symbol) || // is `s` declared within `fun`?
          s == NoSymbol ||                     // is `s` == `_`?
          s.isStatic || {
            c.error(s.pos, "invalid reference to " + s)
            false
          }

        // is tree t a path with only components that satisfy pred? (eg stable or lazy)
        def isPathWith(t: Tree)(pred: TermSymbol => Boolean): Boolean = t match {
          case sel @ Select(s, _) =>
            isPathWith(s)(pred) && pred(sel.symbol.asTerm)
          case id: Ident =>
            pred(id.symbol.asTerm)
          case th: This =>
            true
          // we can't seem to have a super in paths because of S-1938, pity
          // https://issues.scala-lang.org/browse/SI-1938
          // case supr: Super =>
          //   true
          case _ =>
            false
        }

        // traverse the spore body and collect symbols in `capture` invocations
        val collectCapturedTraverser = new Traverser {
          override def traverse(tree: Tree): Unit = tree match {
            case app @ Apply(fun, List(captured)) if (fun.symbol == captureSym) =>
              debug("found capture: " + app)
              if (!isPathWith(captured)(_.isStable))
                c.error(captured.pos, "Only stable paths can be captured")
              else if (!isPathWith(captured)(!_.isLazy))
                c.error(captured.pos, "A captured path cannot contain lazy members")
              else
                capturedSyms ::= captured.symbol
            case _ =>
              super.traverse(tree)
          }
        }
        debug("collecting captured symbols")
        collectCapturedTraverser.traverse(body)

        debug(s"checking $body...")
        // check the spore body, ie for all identifiers, check that they are valid according to spore rules
        // ie, either declared locally or captured via a `capture` invocation
        val traverser = new Traverser {
          override def traverse(tree: Tree) {
            tree match {
              case id: Ident =>
                debug("checking ident " + id)
                isSymbolValid(id.symbol)

              // x.m().s
              case sel @ Select(app @ Apply(fun0, args0), _) =>
                debug("checking select (app): " + sel)
                if (app.symbol.isStatic) {
                  debug("OK, fun static")
                } else fun0 match {
                  case Select(obj, _) =>
                    if (ownerChainContains(obj.symbol, fun.symbol)) debug(s"OK, selected on local object $obj")
                    else c.error(sel.pos, "the fun is not static")
                  case _ =>
                    c.error(sel.pos, "the fun is not static")
                }

              case sel @ Select(pre, _) =>
                debug("checking select " + sel)
                if (!sel.symbol.isMethod)
                  isSymbolValid(sel.symbol)

              case _ =>
                super.traverse(tree)
            }
          }
        }

        traverser.traverse(body)
        (vparams.map(_.symbol), body.tpe, body, validEnv)

      case _ =>
        c.error(funLiteral.pos, "Incorrect usage of `spore`: function literal expected")
        (null, null, null, validEnv)
    }
  }

  def check2(funTree: c.Tree, tpes: List[c.Type]): c.Tree = {
    debug(s"SPORES: enter check2")

    val (paramSyms, retTpe, funBody, validEnv) = conforms(funTree)

    val applyParamNames = for (i <- 0 until paramSyms.size) yield c.fresh(newTermName("x" + i))
    val ids = for (name <- applyParamNames.toList) yield Ident(name)

    val applyParamValDefs = for ((applyParamName, paramSym) <- applyParamNames.zip(paramSyms))
      yield ValDef(Modifiers(Flag.PARAM), applyParamName, TypeTree(paramSym.typeSignature), EmptyTree)
    val applyParamSymbols = for (applyParamValDef <- applyParamValDefs)
      yield applyParamValDef.symbol

    def mkApplyDefDef(body: Tree): DefDef = {
      val applyVParamss = List(applyParamValDefs.toList)
      DefDef(NoMods, newTermName("apply"), Nil, applyVParamss, TypeTree(retTpe), body)
    }

    val symtable = c.universe.asInstanceOf[scala.reflect.internal.SymbolTable]

    def processFunctionBody(substituter: symtable.TreeSubstituter, funBody: Tree): DefDef = {
      val newFunBody = substituter.transform(funBody.asInstanceOf[symtable.Tree])
      val nfBody     = c.resetLocalAttrs(newFunBody.asInstanceOf[Tree])
      mkApplyDefDef(nfBody)
    }

    val sporeClassName = c.fresh(newTypeName("anonspore"))

    if (validEnv.isEmpty) {
      // replace references to paramSyms with references to applyParamSymbols
      val substituter = new symtable.TreeSubstituter(paramSyms.map(_.asInstanceOf[symtable.Symbol]), ids.toList.map(_.asInstanceOf[symtable.Tree]))
      val applyDefDef = processFunctionBody(substituter, funBody)

      if (paramSyms.size == 2) {
        val rtpe  = tpes(0)
        val t1tpe = tpes(1)
        val t2tpe = tpes(2)
        q"""
          class $sporeClassName extends scala.spores.Spore2[$t1tpe, $t2tpe, $rtpe] {
            this._className = this.getClass.getName
            $applyDefDef
          }
          new $sporeClassName
        """
      } else if (paramSyms.size == 3) {
        q"""
          class $sporeClassName extends scala.spores.Spore3[${tpes(1)}, ${tpes(2)}, ${tpes(3)}, ${tpes(0)}] {
            this._className = this.getClass.getName
            $applyDefDef
          }
          new $sporeClassName
        """
      } else ???
    } else { // validEnv.size > 1 (TODO: size == 1)
        // replace references to paramSyms with references to applyParamSymbols
        // and references to captured variables to new fields
        val capturedTypes = validEnv.map(_.typeSignature)
        debug(s"capturedTypes: ${capturedTypes.mkString(",")}")

        val symsToReplace     = (paramSyms ::: validEnv).map(_.asInstanceOf[symtable.Symbol])
        val newTrees          = (1 to validEnv.size).map(i => Select(Ident(newTermName("captured")), newTermName(s"_$i"))).toList
        val treesToSubstitute = (ids ::: newTrees).map(_.asInstanceOf[symtable.Tree])
        val substituter       = new symtable.TreeSubstituter(symsToReplace, treesToSubstitute)
        val applyDefDef       = processFunctionBody(substituter, funBody)

        val rhss = funTree match {
          case Block(stmts, expr) =>
            stmts.toList flatMap {
              case ValDef(_, _, _, rhs) => List(rhs)
              case stmt =>
                c.error(stmt.pos, "Only val defs allowed at this position")
                List()
            }
        }

        val initializerName = c.fresh(newTermName(s"initialize"))
        val initializerTree = q"$initializerName.captured = (..$rhss)"

        val captureTypeTree = (if (capturedTypes.size == 2) q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)})"
          else if (capturedTypes.size == 3) q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(2)})"
          else if (capturedTypes.size == 4) q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(2)}, ${capturedTypes(3)})"
          else if (capturedTypes.size == 5) q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(2)}, ${capturedTypes(3)}, ${capturedTypes(4)})"
          else if (capturedTypes.size == 6) q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(2)}, ${capturedTypes(3)}, ${capturedTypes(4)}, ${capturedTypes(5)})"
          else if (capturedTypes.size == 7) q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(2)}, ${capturedTypes(3)}, ${capturedTypes(4)}, ${capturedTypes(5)}, ${capturedTypes(6)})"
          else if (capturedTypes.size == 8) q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(2)}, ${capturedTypes(3)}, ${capturedTypes(4)}, ${capturedTypes(5)}, ${capturedTypes(6)}, ${capturedTypes(7)})").asInstanceOf[c.Tree]

        if (paramSyms.size == 2) {
          val rtpe  = tpes(0)
          val t1tpe = tpes(1)
          val t2tpe = tpes(2)
          q"""
            final class $sporeClassName extends scala.spores.Spore2WithEnv[$t1tpe, $t2tpe, $rtpe] {
              $captureTypeTree
              this._className = this.getClass.getName
              $applyDefDef
            }
            val $initializerName = new $sporeClassName
            $initializerTree
            $initializerName
          """
        } else if (paramSyms.size == 3) {
          q"""
            final class $sporeClassName extends scala.spores.Spore3WithEnv[${tpes(1)}, ${tpes(2)}, ${tpes(3)}, ${tpes(0)}] {
              $captureTypeTree
              this._className = this.getClass.getName
              $applyDefDef
            }
            val $initializerName = new $sporeClassName
            $initializerTree
            $initializerName
          """
        } else ???
    }
  }

  /**
     spore {
       val x = outer
       (y: T) => { ... }
     }
   */
  def check(funTree: c.Tree, ttpe: c.Type, rtpe: c.Type): c.Tree = {
    debug(s"SPORES: enter check")

    val (paramSyms, retTpe, funBody, validEnv) = conforms(funTree)
    val paramSym = paramSyms.head

    if (paramSym != null) {
      val applyParamName = c.fresh(newTermName("x"))
      val id = Ident(applyParamName)
      val applyName = newTermName("apply")

      val applyParamValDef = ValDef(Modifiers(Flag.PARAM), applyParamName, TypeTree(paramSym.typeSignature), EmptyTree)
      val applyParamSymbol = applyParamValDef.symbol

      val symtable = c.universe.asInstanceOf[scala.reflect.internal.SymbolTable]

      if (validEnv.isEmpty) {
        // replace reference to paramSym with reference to applyParamSymbol
        val substituter = new symtable.TreeSubstituter(List(paramSym.asInstanceOf[symtable.Symbol]), List(id.asInstanceOf[symtable.Tree]))
        val newFunBody = substituter.transform(funBody.asInstanceOf[symtable.Tree])

        val nfBody = c.resetLocalAttrs(newFunBody.asInstanceOf[c.universe.Tree])

        val applyDefDef: DefDef = {
          val applyVParamss = List(List(applyParamValDef))
          DefDef(NoMods, applyName, Nil, applyVParamss, TypeTree(retTpe), nfBody)
        }

        val sporeClassName = c.fresh(newTypeName("anonspore"))

        q"""
          class $sporeClassName extends scala.spores.Spore[$ttpe, $rtpe] {
            this._className = this.getClass.getName
            $applyDefDef
          }
          new $sporeClassName
        """
      } else if (validEnv.size == 1) { // TODO: simplify
        // replace reference to paramSym with reference to applyParamSymbol
        // and references to captured variables to new fields
        val capturedTypes = validEnv.map(sym => sym.typeSignature)
        debug(s"capturedTypes: ${capturedTypes.mkString(",")}")

        val fieldNames = (1 to capturedTypes.size).map(i => newTermName(s"c$i")).toList
        val fieldIds   = fieldNames.map(n => Ident(n))

        val symsToReplace = (paramSym :: validEnv).map(_.asInstanceOf[symtable.Symbol])
        val idsToSubstitute = (id :: fieldIds).map(_.asInstanceOf[symtable.Tree])

        val substituter = new symtable.TreeSubstituter(symsToReplace, idsToSubstitute)
        val newFunBody = substituter.transform(funBody.asInstanceOf[symtable.Tree])

        val nfBody = c.resetLocalAttrs(newFunBody.asInstanceOf[c.universe.Tree])
        val applyDefDef: DefDef = {
          val applyVParamss = List(List(applyParamValDef))
          DefDef(NoMods, applyName, Nil, applyVParamss, TypeTree(retTpe), nfBody)
        }

        val rhss = funTree match {
          case Block(stmts, expr) =>
            stmts.toList flatMap { stmt =>
              stmt match {
                case vd @ ValDef(mods, name, tpt, rhs) => List(rhs)
                case _ =>
                  c.error(stmt.pos, "Only val defs allowed at this position")
                  List()
              }
            }
        }

        val sporeClassName = c.fresh(newTypeName("anonspore"))
        val initializerNames = (1 to capturedTypes.size).map(i => c.fresh(newTermName(s"initialize$i")))

        val initializerName = c.fresh(newTermName(s"initialize"))
        val initializerTrees = fieldNames.zip(rhss).zipWithIndex.map {
          case ((n, rhs), i) =>
            val t = newTypeName("C" + (i+1))
            q"$initializerName.$n = $rhs.asInstanceOf[$initializerName.$t]"
        }

        val fieldTrees = fieldNames.zipWithIndex.map {
          case (n, i) =>
            val t = newTypeName("C" + (i+1))
            q"var $n: $t = _"
        }

        val superclassName = newTypeName(s"SporeC${capturedTypes.size}")
        val captureTypeTree = (if (capturedTypes.size == 1) q"type Captured = ${capturedTypes(0)}"
          else if (capturedTypes.size == 2) q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)})"
          else if (capturedTypes.size == 3) q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(2)})"
          else if (capturedTypes.size == 4) q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(2)}, ${capturedTypes(3)})").asInstanceOf[c.Tree]

        val cTypeTrees = capturedTypes.zipWithIndex.map {
          case (t, i) =>
            val n = newTypeName("C" + (i + 1))
            q"type $n = $t"
        }

        q"""
          final class $sporeClassName extends $superclassName[$ttpe, $rtpe] {
            $captureTypeTree
            ..$cTypeTrees
            ..$fieldTrees
            this._className = this.getClass.getName
            $applyDefDef
          }
          val $initializerName = new $sporeClassName
          ..$initializerTrees
          $initializerName
        """
      } else { // validEnv.size > 1
        // replace reference to paramSym with reference to applyParamSymbol
        // and references to captured variables to new fields
        val capturedTypes = validEnv.map(sym => sym.typeSignature)
        debug(s"capturedTypes: ${capturedTypes.mkString(",")}")

        val symsToReplace = (paramSym :: validEnv).map(_.asInstanceOf[symtable.Symbol])
        val newTrees = (1 to validEnv.size).map(i => Select(Ident(newTermName("captured")), newTermName(s"_$i"))).toList
        val treesToSubstitute = (id :: newTrees).map(_.asInstanceOf[symtable.Tree])

        val substituter = new symtable.TreeSubstituter(symsToReplace, treesToSubstitute)
        val newFunBody = substituter.transform(funBody.asInstanceOf[symtable.Tree])

        val nfBody = c.resetLocalAttrs(newFunBody.asInstanceOf[c.universe.Tree])
        val applyDefDef: DefDef = {
          val applyVParamss = List(List(applyParamValDef))
          DefDef(NoMods, applyName, Nil, applyVParamss, TypeTree(retTpe), nfBody)
        }

        val rhss = funTree match {
          case Block(stmts, expr) =>
            stmts.toList flatMap { stmt =>
              stmt match {
                case vd @ ValDef(mods, name, tpt, rhs) => List(rhs)
                case _ =>
                  c.error(stmt.pos, "Only val defs allowed at this position")
                  List()
              }
            }
        }

        val sporeClassName  = c.fresh(newTypeName("anonspore"))
        val initializerName = c.fresh(newTermName(s"initialize"))
        val initializerTree = q"$initializerName.captured = (..$rhss)"
        val superclassName  = newTypeName(s"SporeWithEnv")

        val captureTypeTree = (if (capturedTypes.size == 2) q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)})"
          else if (capturedTypes.size == 3) q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(2)})"
          else if (capturedTypes.size == 4) q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(2)}, ${capturedTypes(3)})"
          else if (capturedTypes.size == 5) q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(2)}, ${capturedTypes(3)}, ${capturedTypes(4)})"
          else if (capturedTypes.size == 6) q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(2)}, ${capturedTypes(3)}, ${capturedTypes(4)}, ${capturedTypes(5)})"
          else if (capturedTypes.size == 7) q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(2)}, ${capturedTypes(3)}, ${capturedTypes(4)}, ${capturedTypes(5)}, ${capturedTypes(6)})"
          else if (capturedTypes.size == 8) q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(2)}, ${capturedTypes(3)}, ${capturedTypes(4)}, ${capturedTypes(5)}, ${capturedTypes(6)}, ${capturedTypes(7)})").asInstanceOf[c.Tree]

        q"""
          final class $sporeClassName extends $superclassName[$ttpe, $rtpe] {
            $captureTypeTree
            this._className = this.getClass.getName
            $applyDefDef
          }
          val $initializerName = new $sporeClassName
          $initializerTree
          $initializerName
        """
      }
    } else {
      ???
    }
  }

}
