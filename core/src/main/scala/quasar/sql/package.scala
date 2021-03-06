/*
 * Copyright 2014–2016 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar

import quasar.Predef._
import quasar.fp._
import quasar.fs._

import matryoshka._, Recursive.ops._, FunctorT.ops._, TraverseT.nonInheritedOps._
import monocle.Prism
import pathy.Path.posixCodec
import scalaz._, Scalaz._

package object sql {
  def select[A] = Prism.partial[Sql[A], (IsDistinct, List[Proj[A]], Option[SqlRelation[A]], Option[A], Option[GroupBy[A]], Option[OrderBy[A]])] {
    case Select(d, p, r, f, g, o) => (d, p, r, f, g, o)
  } ((Select[A] _).tupled)

  def vari[A] = Prism.partial[Sql[A], String] { case Vari(sym) => sym } (Vari(_))
  def setLiteral[A] = Prism.partial[Sql[A], List[A]] { case SetLiteral(exprs) => exprs } (SetLiteral(_))
  def arrayLiteral[A] = Prism.partial[Sql[A], List[A]] { case ArrayLiteral(exprs) => exprs } (ArrayLiteral(_))
  def mapLiteral[A] = Prism.partial[Sql[A], List[(A, A)]] { case MapLiteral(exprs) => exprs } (MapLiteral(_))
  def splice[A] = Prism.partial[Sql[A], Option[A]] { case Splice(exprs) => exprs } (Splice(_))
  def binop[A] = Prism.partial[Sql[A], (A, A, BinaryOperator)] { case Binop(l, r, op) => (l, r, op) } ((Binop[A] _).tupled)
  def unop[A] = Prism.partial[Sql[A], (A, UnaryOperator)] { case Unop(a, op) => (a, op) } ((Unop[A] _).tupled)
  def ident[A] = Prism.partial[Sql[A], String] { case Ident(name) => name } (Ident(_))
  def invokeFunction[A] = Prism.partial[Sql[A], (String, List[A])] { case InvokeFunction(name, args) => (name, args) } ((InvokeFunction[A] _).tupled)
  def matc[A] = Prism.partial[Sql[A], (A, List[Case[A]], Option[A])] { case Match(expr, cases, default) => (expr, cases, default) } ((Match[A] _).tupled)
  def switch[A] = Prism.partial[Sql[A], (List[Case[A]], Option[A])] { case Switch(cases, default) => (cases, default) } ((Switch[A] _).tupled)
  def let[A] = Prism.partial[Sql[A], (String, A, A)] { case Let(n, f, b) => (n, f, b) } ((Let[A](_, _, _)).tupled)
  def intLiteral[A] = Prism.partial[Sql[A], Long] { case IntLiteral(v) => v } (IntLiteral(_))
  def floatLiteral[A] = Prism.partial[Sql[A], Double] { case FloatLiteral(v) => v } (FloatLiteral(_))
  def stringLiteral[A] = Prism.partial[Sql[A], String] { case StringLiteral(v) => v } (StringLiteral(_))
  def nullLiteral[A] = Prism.partial[Sql[A], Unit] { case NullLiteral() => () } (_ => NullLiteral())
  def boolLiteral[A] = Prism.partial[Sql[A], Boolean] { case BoolLiteral(v) => v } (BoolLiteral(_))

  // NB: we need to support relative paths, including `../foo`
  type FUPath = pathy.Path[_, pathy.Path.File, pathy.Path.Unsandboxed]

  private def parser[T[_[_]]: Recursive: Corecursive] = new SQLParser[T]()

  // NB: Statically allocated to avoid multiple allocations of the parser.
  val muParser = parser[Mu]
  // TODO: Get rid of this one once we’ve parameterized everything on `T`.
  val fixParser = parser[Fix]

  def CrossRelation[T[_[_]]: Corecursive](left: SqlRelation[T[Sql]], right: SqlRelation[T[Sql]]) =
    JoinRelation(left, right, InnerJoin, boolLiteral[T[Sql]](true).embed)

  def projectionNames[T[_[_]]: Recursive](
    projections: List[Proj[T[Sql]]], relName: Option[String]):
      SemanticError \/ List[(String, T[Sql])] = {
    def extractName(expr: T[Sql]): Option[String] = expr.project match {
      case Ident(name) if Some(name) != relName          => name.some
      case Binop(_, Embed(StringLiteral(v)), FieldDeref) => v.some
      case Unop(expr, FlattenMapValues)                  => extractName(expr)
      case Unop(expr, FlattenArrayValues)                => extractName(expr)
      case _                                             => None
    }

    val aliases = projections.flatMap{ case Proj(expr, alias) => alias.toList}

    (aliases diff aliases.distinct).headOption.cata(
      duplicateAlias => SemanticError.DuplicateAlias(duplicateAlias).left,
      projections.zipWithIndex.mapAccumLeft1(aliases.toSet) { case (used, (Proj(expr, alias), index)) =>
        alias.cata(
          a => (used, a -> expr),
          {
            val tentativeName = extractName(expr) getOrElse index.toString
            val alternatives = Stream.from(0).map(suffix => tentativeName + suffix.toString)
            val name = (tentativeName #:: alternatives).dropWhile(used.contains).head
            (used + name, name -> expr)
          })
      }._2.right)
  }

  def mapPathsMƒ[F[_]: Monad](f: FUPath => F[FUPath]): Sql ~> (F ∘ Sql)#λ =
    new (Sql ~> (F ∘ Sql)#λ) {
      def apply[A](e: Sql[A]) = e match {
        case Select(d, p, relations, filt, g, o) =>
          relations.traverse(_.mapPathsM(f)).map(Select(d, p, _, filt, g, o))
        case x => x.point[F]
      }
    }

  implicit class ExprOps[T[_[_]]: Recursive: Corecursive](q: T[Sql]) {
    def mkPathsAbsolute(basePath: ADir): T[Sql] =
      q.transCata(mapPathsMƒ[Id](refineTypeAbs(_).fold(ι, pathy.Path.unsandbox(basePath) </> _)))

    def makeTables(bindings: List[String]): T[Sql] = q.project match {
      case sel @ Select(_, _, _, _, _, _) => {
        // perform all the appropriate recursions
        // TODO remove asInstanceOf by providing a `SelectF` at compile time
        val sel2 = Functor[Sql].map(sel)(_.makeTables(bindings)).asInstanceOf[Select[T[Sql]]]

        def mkRel[A](rel: SqlRelation[A]): SqlRelation[A] = rel match {
          case ir @ IdentRelationAST(name, alias) if !bindings.contains(name) => {
            val filePath = posixCodec.parsePath(Some(_),Some(_),_ => None, _ => None)(name)
            filePath.map(p => TableRelationAST[A](p, alias)).getOrElse(ir)
          }

          case JoinRelation(left, right, tpe, clause) =>
            JoinRelation(mkRel(left), mkRel(right), tpe, clause)

          case other => other
        }

        // TODO use lenses
        sel2.copy(relations = sel2.relations.map(mkRel(_))).embed
      }

      case Let(name, form, body) => {
        val form2 = form.makeTables(bindings)
        val body2 = body.makeTables(name :: bindings)
        let(name, form2, body2).embed
      }

      case other => other.map(_.makeTables(bindings)).embed
    }
  }

  def pprint[T[_[_]]: Recursive](sql: T[Sql]) = sql.para(pprintƒ)

  private val SimpleNamePattern = "[_a-zA-Z][_a-zA-Z0-9]*".r

  private def _q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  private def _qq(delimiter: String, s: String): String = s match {
    case SimpleNamePattern() => s
    case _                   => delimiter + s.replace("\\", "\\\\").replace(delimiter, "\\`") + delimiter
  }

  private def pprintRelationƒ[T[_[_]]: Recursive](r: SqlRelation[(T[Sql], String)]):
      String =
    (r match {
      case IdentRelationAST(name, alias) =>
        _qq("`", name) :: alias.map("as " + _).toList
      case TableRelationAST(path, alias) =>
        _qq("`", prettyPrint(path)) :: alias.map("as " + _).toList
      case ExprRelationAST(expr, aliasName) =>
        List(expr._2, "as", aliasName)
      case JoinRelation(left, right, tpe, clause) =>
        (tpe, clause._1) match {
          case (InnerJoin, Embed(BoolLiteral(true))) =>
            List("(", pprintRelationƒ(left), "cross join", pprintRelationƒ(right), ")")
          case (_, _) =>
            List("(", pprintRelationƒ(left), tpe.sql, pprintRelationƒ(right), "on", clause._2, ")")
        }
    }).mkString(" ")

  def pprintRelation[T[_[_]]: Recursive](r: SqlRelation[T[Sql]]) =
    pprintRelationƒ(traverseRelation[Id, T[Sql], (T[Sql], String)](r, x => (x, pprint(x))))

  def pprintƒ[T[_[_]]: Recursive]: Sql[(T[Sql], String)] => String = {
    def caseSql(c: Case[(T[Sql], String)]): String =
      List("when", c.cond._2, "then", c.expr._2) mkString " "

    {
      case Select(
        isDistinct,
        projections,
        relations,
        filter,
        groupBy,
        orderBy) =>
        "(" +
        List(
          Some("select"),
          isDistinct match { case `SelectDistinct` => Some("distinct"); case _ => None },
          Some(projections.map(p => p.alias.foldLeft(p.expr._2)(_ + " as " + _qq("`", _))).mkString(", ")),
          relations.map(r => "from " + pprintRelationƒ(r)),
          filter.map("where " + _._2),
          groupBy.map(g =>
            ("group by" ::
              g.keys.map(_._2).mkString(", ") ::
              g.having.map("having " + _._2).toList).mkString(" ")),
          orderBy.map(o => List("order by", o.keys.map(x => x._2._2 + " " + x._1.toString) mkString(", ")).mkString(" "))).foldMap(_.toList).mkString(" ") +
        ")"
      case Vari(symbol) => ":" + symbol
      case SetLiteral(exprs) => exprs.map(_._2).mkString("(", ", ", ")")
      case ArrayLiteral(exprs) => exprs.map(_._2).mkString("[", ", ", "]")
      case MapLiteral(exprs) => exprs.map {
        case (k, v) => k._2 + ": " + v._2
      }.mkString("{", ", ", "}")
      case Splice(expr) => expr.fold("*")("(" + _._2 + ").*")
      case Binop(lhs, rhs, op) => op match {
        case FieldDeref => rhs._1.project match {
          case StringLiteral(str) => "(" + lhs._2 + ")." + _qq("\"", str)
          case _                   => "(" + lhs._2 + "){" + rhs._2 + "}"
        }
        case IndexDeref => "(" + lhs._2 + ")[" + rhs._2 + "]"
        case _ => List("(" + lhs._2 + ")", op.sql, "(" + rhs._2 + ")").mkString(" ")
      }
      case Unop(expr, op) => op match {
        case FlattenMapKeys      => "(" + expr._2 + "){*:}"
        case FlattenMapValues    => "(" + expr._2 + "){:*}"
        case ShiftMapKeys        => "(" + expr._2 + "){_:}"
        case ShiftMapValues      => "(" + expr._2 + "){:_}"
        case FlattenArrayIndices => "(" + expr._2 + ")[*:]"
        case FlattenArrayValues  => "(" + expr._2 + ")[:*]"
        case ShiftArrayIndices   => "(" + expr._2 + ")[_:]"
        case ShiftArrayValues    => "(" + expr._2 + ")[:_]"
        case _ =>
          val s = List(op.sql, "(", expr._2, ")") mkString " "
          // NB: dis-ambiguates the query in case this is the leading projection
          if (op == Distinct) "(" + s + ")" else s
      }
      case Ident(name) => _qq("`", name)
      case InvokeFunction(name, args) =>
        import quasar.std.StdLib.string
        (name, args) match {
          case (string.Like.name, (_, value) :: (_, pattern) :: (Embed(StringLiteral("\\")), _) :: Nil) =>
            "(" + value + ") like (" + pattern + ")"
          case (string.Like.name, (_, value) :: (_, pattern) :: (_, esc) :: Nil) =>
            "(" + value + ") like (" + pattern + ") escape (" + esc + ")"
          case _ => name + "(" + args.map(_._2).mkString(", ") + ")"
        }
      case Match(expr, cases, default) =>
        ("case" ::
          expr._2 ::
          ((cases.map(caseSql) ++ default.map("else " + _._2).toList) :+
            "end")).mkString(" ")
      case Switch(cases, default) =>
        ("case" ::
          ((cases.map(caseSql) ++ default.map("else " + _._2).toList) :+
            "end")).mkString(" ")
      case Let(name, form, body) =>
        name ++ " :: " ++ form._2 ++ "; " ++ body._2
      case IntLiteral(v) => v.toString
      case FloatLiteral(v) => v.toString
      case StringLiteral(v) => _q(v)
      case NullLiteral() => "null"
      case BoolLiteral(v) => if (v) "true" else "false"
    }
  }

  def normalizeƒ[T[_[_]]: Corecursive]:
      Sql[T[Sql]] => Option[Sql[T[Sql]]] = {
    case Binop(l, r, Union) =>
      Unop(Binop(l, r, UnionAll).embed, Distinct).some
    case Binop(l, r, Intersect) =>
      Unop(Binop(l, r, IntersectAll).embed, Distinct).some
    case _ => None
  }

  def traverseRelation[G[_], A, B](r: SqlRelation[A], f: A => G[B])(
    implicit G: Applicative[G]):
      G[SqlRelation[B]] =
    r match {
      case IdentRelationAST(name, alias) =>
        G.point(IdentRelationAST(name, alias))
      case TableRelationAST(name, alias) =>
        G.point(TableRelationAST(name, alias))
      case ExprRelationAST(expr, aliasName) =>
        G.apply(f(expr))(ExprRelationAST(_, aliasName))
      case JoinRelation(left, right, tpe, clause) =>
        G.apply3(traverseRelation(left, f), traverseRelation(right, f), f(clause))(
          JoinRelation(_, _, tpe, _))
    }

  private val astType = "AST" :: Nil

  implicit def ExprRelationRenderTree: RenderTree ~> λ[α => RenderTree[SqlRelation[α]]] =
    new (RenderTree ~> λ[α => RenderTree[SqlRelation[α]]]) {
      def apply[α](ra: RenderTree[α]) = new RenderTree[SqlRelation[α]] {
        def render(r: SqlRelation[α]): RenderedTree = r match {
          case IdentRelationAST(name, alias) =>
            val aliasString = alias.cata(" as " + _, "")
            Terminal("IdentRelation" :: astType, Some(name + aliasString))
          case ExprRelationAST(select, alias) =>
            NonTerminal("ExprRelation" :: astType, Some("Expr as " + alias), ra.render(select) :: Nil)
          case TableRelationAST(name, alias) =>
            val aliasString = alias.cata(" as " + _, "")
            Terminal("TableRelation" :: astType, Some(prettyPrint(name) + aliasString))
          case JoinRelation(left, right, jt, clause) =>
            NonTerminal("JoinRelation" :: astType, Some(jt.toString),
              List(render(left), render(right), ra.render(clause)))
        }
      }
    }

  implicit val SqlRenderTree: RenderTree ~> λ[α => RenderTree[Sql[α]]] =
    new (RenderTree ~> λ[α => RenderTree[Sql[α]]]) {
      def apply[α](ra: RenderTree[α]): RenderTree[Sql[α]] = new RenderTree[Sql[α]] {
        def renderCase(c: Case[α]): RenderedTree =
          NonTerminal("Case" :: astType, None, ra.render(c.cond) :: ra.render(c.expr) :: Nil)

        def render(n: Sql[α]) = n match {
          case Select(isDistinct, projections, relations, filter, groupBy, orderBy) =>
            val nt = "Select" :: astType
            NonTerminal(nt,
              isDistinct match { case `SelectDistinct` => Some("distinct"); case _ => None },
              projections.map { p =>
                NonTerminal("Proj" :: astType, p.alias, ra.render(p.expr) :: Nil)
              } ⊹
                (relations.map(ExprRelationRenderTree(ra).render) ::
                  filter.map(ra.render) ::
                  groupBy.map {
                    case GroupBy(keys, Some(having)) => NonTerminal("GroupBy" :: astType, None, keys.map(ra.render) :+ ra.render(having))
                    case GroupBy(keys, None)         => NonTerminal("GroupBy" :: astType, None, keys.map(ra.render))
                  } ::
                  orderBy.map {
                    case OrderBy(keys) =>
                      val nt = "OrderBy" :: astType
                      NonTerminal(nt, None,
                        keys.map { case (t, x) => NonTerminal("OrderType" :: nt, Some(t.toString), ra.render(x) :: Nil)})
                  } ::
                  Nil).foldMap(_.toList))

          case SetLiteral(exprs) => NonTerminal("Set" :: astType, None, exprs.map(ra.render))
          case ArrayLiteral(exprs) => NonTerminal("Array" :: astType, None, exprs.map(ra.render))
          case MapLiteral(exprs) => NonTerminal("Map" :: astType, None, exprs.map(Tuple2RenderTree(ra, ra).render))

          case InvokeFunction(name, args) => NonTerminal("InvokeFunction" :: astType, Some(name), args.map(ra.render))

          case Match(expr, cases, Some(default)) => NonTerminal("Match" :: astType, None, ra.render(expr) :: (cases.map(renderCase) :+ ra.render(default)))
          case Match(expr, cases, None)          => NonTerminal("Match" :: astType, None, ra.render(expr) :: cases.map(renderCase))

          case Switch(cases, Some(default)) => NonTerminal("Switch" :: astType, None, cases.map(renderCase) :+ ra.render(default))
          case Switch(cases, None)          => NonTerminal("Switch" :: astType, None, cases.map(renderCase))

          case Binop(lhs, rhs, op) => NonTerminal("Binop" :: astType, Some(op.toString), ra.render(lhs) :: ra.render(rhs) :: Nil)

          case Unop(expr, op) => NonTerminal("Unop" :: astType, Some(op.sql), ra.render(expr) :: Nil)

          case Splice(expr) => NonTerminal("Splice" :: astType, None, expr.toList.map(ra.render))

          case Ident(name) => Terminal("Ident" :: astType, Some(name))

          case Vari(name) => Terminal("Variable" :: astType, Some(":" + name))

          case Let(name, form, body) =>
            NonTerminal("Let" :: astType, Some(name), ra.render(form) :: ra.render(body) :: Nil)

          case IntLiteral(v) => Terminal("LiteralExpr" :: astType, Some(v.shows))
          case FloatLiteral(v) => Terminal("LiteralExpr" :: astType, Some(v.shows))
          case StringLiteral(v) => Terminal("LiteralExpr" :: astType, Some(v.shows))
          case NullLiteral() => Terminal("LiteralExpr" :: astType, None)
          case BoolLiteral(v) => Terminal("LiteralExpr" :: astType, Some(v.shows))
        }
      }
    }
}
