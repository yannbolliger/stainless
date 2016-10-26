import stainless.annotation._
import stainless.collection._
import stainless.lang._

object AbstractRefinementMap {

  case class ~>[A,B](private val f: A => B, pre: A => Boolean, ens: B => Boolean) {
    def apply(x: A): B = {
      require(pre(x))
      f(x)
    } ensuring(ens)
  }

  def map[A, B](l: List[A], f: A ~> B): List[B] = {
    require(forall((x:A) => l.contains(x) ==> f.pre(x)))
    l match {
      case Cons(x, xs) => Cons[B](f(x), map(xs, f))
      case Nil() => Nil[B]()
    }
  } ensuring { res => forall((x: B) => res.contains(x) ==> f.ens(x)) }
}

