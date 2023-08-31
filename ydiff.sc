#!/usr/bin/env -S scala-cli shebang
//> using scala 3.3.0
//> using dep com.zhranklin::scala-tricks:0.2.5
//> using dep com.flipkart.zjsonpatch:zjsonpatch:0.4.14
//> using dep com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.14.3
//> using dep com.lihaoyi::os-lib:0.9.1
//> using dep io.github.azagniotov:ant-style-path-matcher:1.0.0
//> using dep org.fusesource.jansi:jansi:2.4.0
//> using dep io.github.java-diff-utils:java-diff-utils:4.12
//> using dep com.github.scopt::scopt:4.1.0
//> using dep org.scala-lang.modules::scala-parser-combinators:2.3.0

import java.io.File
import scala.collection.mutable
import scala.collection.MapView
import scala.language.dynamics._

import com.fasterxml.jackson.databind.node.{ArrayNode, JsonNodeFactory, ObjectNode, TextNode, MissingNode}
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.flipkart.zjsonpatch.{DiffFlags, JsonDiff}
import com.github.difflib.text.DiffRowGenerator
import zrkn.op._
import com.fasterxml.jackson.databind.node.NullNode
import java.util.regex.Matcher
import scala.language.dynamics

import scala.jdk.CollectionConverters._
import io.github.azagniotov.matcher.AntPathMatcher;

import java.util.regex.Pattern
import scala.util.boundary, boundary.break

import zrkn.op.given
import os.{root, read, Path, CommandResult, pwd}
import scala.util.Try
import scala.collection.mutable.ListBuffer

val jsonMapper = new ObjectMapper

def getPath(p: String) =
  if p.equals("-") then
    root/"dev"/"stdin"
  else
    Path.expandUser(p, pwd)

def yamlFactory =
  import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature._
  new YAMLFactory()
    .disable(WRITE_DOC_START_MARKER)
    .enable(MINIMIZE_QUOTES)

def groups(str: String) = "\\(".r.findAllIn(str).size - """\(\?([idmsuxU=>!:]|<[!=])""".r.findAllIn(str).size
class Interped(sc: StringContext):
  def unapplySeq(s: String): Option[Seq[String]] =
    val parts = sc.parts
    val tail = parts.tail.map(s => if s.startsWith("(") then s else "(.*)" + s)
    val pattern = Pattern.compile(parts.head + tail.mkString)
    var groupCount = groups(parts.head)
    val usedGroup = tail.map: part =>
      val ret = groupCount
      groupCount += groups(part)
      ret
    val m = pattern matcher s
    if m.matches() then
      Some(usedGroup.map(i => m.group(i + 1)))
    else None

extension (sc: StringContext) def rr: Interped = new Interped(sc)

object c:
  extension (s: String)
    def unescapePath: String = s.replaceAll("~0", "~").replaceAll("~1", "/")

  import org.fusesource.jansi.Ansi.{Color, ansi}
  import org.fusesource.jansi.Ansi.Attribute._
  val MARK_DELETE_LINE = "<DELETE_LINE>"
  val MARK_MODIFY_LINE = "<MODIFY_LINE>"
  val MARK_ADD_LINE = "<INSERT_LINE>"
  val TAG_DELETE = "<DELETE>"
  val TAG_ADD = "<ADD>"
  val TAG_END = "<RESET>"
  val MARK_DELETE_FIELD = "<DELETE_FIELD>"
  val MARK_ADD_FIELD = "<ADD_FIELD>"
  val NUMBER_KEY = "<YDIFF_NUMBER>"
  val TRAILING_SPACE = "<SPACE>"
  val DELETE = ansi().fg(Color.RED).a(STRIKETHROUGH_ON).a(INTENSITY_BOLD).toString
  val ADD = ansi().fg(Color.GREEN).a(INTENSITY_BOLD).toString
  val RESET = ansi().reset().toString
  val MINUS = ansi().render("@|red -|@").toString
  val PLUS = ansi().render("@|green +|@").toString
  val TILDE = ansi().render("@|yellow ~|@").toString
import c.unescapePath

trait ValueMatcher:
  val newNode = JsonNodeFactory.instance.pojoNode _
  def matches(node: JsonNode, basePath: String, value: JsonNode): Boolean
  def jsonEquals(v1: JsonNode, v2: JsonNode) =
    ValueMatcher.jsonMapper.readTree(v1.toString) == (ValueMatcher.jsonMapper.readTree(v2.toString))
trait KeyExtractor:
  def extract(node: JsonNode, basePath: String): Option[String]

object ValueMatcher:
  val jsonMapper = new ObjectMapper()
  def exact(v: Any) = new ValueMatcher:
    val expect = newNode(v)
    def matches(node: JsonNode, basePath: String, value: JsonNode) = jsonEquals(expect, value)
    override def toString = s"exact($v)"

  def getValue(obj: JsonNode, basePath: String, path0: String): Option[JsonNode] =
    val path = path0
      .replaceAll("^(\\./|(?!/))", basePath + "/")
      .replaceAll("[^/]+/\\.\\./", "")
    var cur = obj
    path.split("/").dropRight(1).drop(1)
      .foreach: pp =>
        val p = pp.replaceAll("~1", "/")
        cur match
          case c: ArrayNode if p.toIntOption.nonEmpty =>
            cur = c.get(p.toInt)
          case c: ObjectNode =>
            cur = c.get(p)
          case _ =>
            println(s"Error extracting $path: $pp is neither array nor object.")
    val last = path.split("/").last
    Option(cur).flatMap(c => Option(c.get(last)))
  end getValue

  def key(path: String) = new KeyExtractor:
    def extract(node: JsonNode, basePath: String): Option[String] =
      getValue(node, basePath, path)
        .map:
          case k: TextNode => k.textValue()
          case k => k.toString
        .map: k =>
          val name = path.replaceAll("^/", "").replaceAll("/", ".")
          val parent = basePath.split("/").filter(_.nonEmpty).last
          s"$parent[$name=$k]"
  def ref(path: String) = new ValueMatcher:
    def matches(node: JsonNode, basePath: String, value: JsonNode) =
      getValue(node, basePath, path).exists(jsonEquals(_, value))
    end matches
    override def toString = s"ref($path)"

  object always extends ValueMatcher:
    def matches(node: JsonNode, basePath: String, value: JsonNode) = true
    override def toString = "always"

val defaultIgnores = """
* {
  /metadata/annotations/deployment.kubernetes.io/revision: always
}
Deployment {
  /spec/progressDeadlineSeconds: exact(600)
  /spec/revisionHistoryLimit: exact(10)
  /spec/template/metadata/creationTimestamp: always
  /spec/strategy/type: exact(RollingUpdate)
  /spec/template/spec/dnsPolicy: exact(ClusterFirst)
  /spec/template/spec/restartPolicy: exact(Always)
  /spec/template/spec/schedulerName: exact(default-scheduler)
  /spec/template/spec/containers/*/terminationMessagePath: exact(/dev/termination-log)
  /spec/template/spec/containers/*/terminationMessagePolicy: exact(File)
  /spec/template/spec/initContainers/*/terminationMessagePath: exact(/dev/termination-log)
  /spec/template/spec/initContainers/*/terminationMessagePolicy: exact(File)
  /spec/template/spec/terminationGracePeriodSeconds: exact(30)
  /spec/template/spec/containers/*/env/*/valueFrom/fieldRef/apiVersion: exact(v1)
}
StatefulSet {
  /spec/revisionHistoryLimit: exact(10)
  /spec/template/metadata/creationTimestamp: always
  /spec/template/spec/dnsPolicy: exact(ClusterFirst)
  /spec/template/spec/restartPolicy: exact(Always)
  /spec/template/spec/schedulerName: exact(default-scheduler)
  /spec/template/spec/containers/*/terminationMessagePath: exact(/dev/termination-log)
  /spec/template/spec/containers/*/terminationMessagePolicy: exact(File)
  /spec/template/spec/terminationGracePeriodSeconds: exact(30)
  /spec/template/spec/containers/*/env/*/valueFrom/fieldRef/apiVersion: exact(v1)
}
Service {
  /spec/clusterIP: always
}
* {
  /spec/template/spec/containers/*/env: key(/name)
  /spec/template/spec/containers/*/volumeMounts: key(/name)
  /spec/template/spec/volumes: key(/name)
}
"""

object KubectlParser extends scala.util.parsing.combinator.RegexParsers:
  import scala.util.parsing.combinator._
  val nonSpace = "[^ ]+".r
  type M = List[(String, String)]
  type PM = Parser[M]
  def args: PM = (nsOpt | fileOpt | oyaml | replicasOpt | normalArg).+ ^^ { _.fold(List.empty[(String, String)])(_++_) }
  def nsOpt: PM = ("-n" | "--namespace") ~! nonSpace ^^ { case _ ~ ns => List("namespace" -> ns) }
  def fileOpt: PM = ("-f" | "--file") ~! nonSpace ^^ { case _ ~ ns => List("file" -> ns) }
  def replicasOpt: PM = ("--replicas=\\d+".r) ^^ { case rs => List("replicas" -> rs.split("=")(1)) } | "--replicas" ~! "\\d+".r ^^ { case _ ~ rs => List("replicas" -> rs) }
  def oyaml: PM = (("-o" ~! "yaml")) ^^ {_ => List("format" -> "yaml")}
  def normalArg: PM = nonSpace ^^ {a => List("arg" -> a)}
  def parse0(s: String) = parseAll(args, s) match
    case Success(matched, _) => matched
    case Failure(msg, _) =>
      println(msg)
      throw new Exception
    case Error(msg, _) =>
      println(msg)
      throw new Exception
extension (p: KubectlParser.M) def apply(key: String) = p.filter(_._1 == key).map(_._2)

object KubectlExec:
  import KubectlParser.{M, PM, parse0}
  val source = getPath(System.getenv("YDIFF_KUBECTL_SOURCE"))
  val db = source / os.up / source.last.replaceFirst("((\\.yaml|\\.yml|)$)", ".modified$1")

  def doApply(cmd: String, inputs: List[String]) =
    import Models._
    var result = (read(db) :: inputs)
      .map: f =>
        new YamlDocs.Static(f, true).sourceObjs(using DiffArgs(flags = Set("k8s"))).toMap
      .reduce: (source, added) =>
        val List(sk, ak) = List(source, added).map(_.keySet)
        val (exists, nonExists) = (sk.intersect(ak), ak.removedAll(sk))
        val appliedKeys = cmd match
          case "apply" => added.keySet
          case "replace" => 
            nonExists.foreach: key =>
              System.err.println(s"ERROR: Resource not found: $key")
            exists
          case "create" => 
            exists.foreach: key =>
              System.err.println(s"ERROR: Resource already exists: $key")
            nonExists
        source ++ added.filterKeys(appliedKeys.contains)
      .values
      .map(doc => "---\n" + doc.yaml)
      .mkString("\n")
    os.write.over(db, result)


  def setImage(tree: JsonNode, expr: String): JsonNode =
    val Array(lhs, rhs) = expr.split("=", 2)
    val containers = tree.get("spec").get("template").get("spec").get("containers").elements
    containers.asScala
      .filter(_.get("name").textValue == lhs)
      .foreach{o => o.asInstanceOf[ObjectNode].put("image", rhs)}
    tree
  def kubeSet(a: M) =
    import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature._
    val ym = new ObjectMapper(new YAMLFactory().disable(WRITE_DOC_START_MARKER))
    def tree(kind: String, name: String) = ym.readTree(doGetOrDelete(kind, a("namespace").headOption, name, false).head.yaml)
    val result = a("arg") match
      case "set" :: "image" :: kind :: name :: expr :: rest => setImage(tree(kind, name), expr)
      case "scale" :: kind :: name :: rest =>
        val t = tree(kind, name)
        t.get("spec").asInstanceOf[ObjectNode].put("replicas", a("replicas").head.toInt)
        t
      case _ =>
        System.err.println(s"Not supported: ${a("arg").mkString(" ")}")
        System.exit(1)
        null
    doApply("apply", ym.writeValueAsString(result) :: Nil)

  def doGetOrDelete(kind: String, ns: Option[String], name: String, delete: Boolean) =
    var notFound = true
    val result = new YamlDocs.Static(read(db), true).sourceObjs(using new ReadOption()).values
      .filter: doc =>
        val id = doc.id.asInstanceOf[Models.GVK]
        val exists = id.kind == kind && id.name == name && ns.forall(_ == id.namespace)
        if exists then notFound = false
        delete ^ exists
      .toList
    if notFound then
      System.err.println(s"Resource Not Found: ${Models.GVK(kind, name, ns.getOrElse(""))}")
      System.exit(1)
    result
    
  def getOrDelete(kind: String, name: String, ns: Option[String], format: Option[String], delete: Boolean) =
    val yaml = doGetOrDelete(kind, ns, name, delete).map(_.yaml).mkString("\n---\n")
    if delete then os.write.over(db,yaml)
    else if format.contains("yaml") then
      println(yaml)
    else
      println(s"Resource Exists: ${Models.GVK(kind, name, ns.getOrElse(""))}")

  def kubectl(args: List[String]) =
    val a = KubectlParser.parse0(args.map(_.replaceAll(" ", "SSPPAACCEE")).mkString(" ")).map(tp => (tp._1, tp._2.replaceAll("SSPPAACCEE", " ")))
    println(s"# ${a.map(tp => tp._1 + "=" + tp._2).mkString(", ")}")
    a("arg") match
      case (cmd @ ("get" | "delete")) :: kind :: name :: _ => getOrDelete(kind, name, a("namespace").headOption, a("format").headOption, cmd == "delete")
      case "restore" :: _ => os.copy.over(source, db)
      case (cmd @ ("replace" | "apply" | "create")) :: _ => doApply(cmd, (a("file").map(getPath)).map(read))
      case ("set" | "scale") :: rest => kubeSet(a)
      case "print" :: _ => println(os.read(db))
      case cmd =>
        println(s"Command $cmd not supported.")
        System.exit(1)

object IgnoreRulesParser extends scala.util.parsing.combinator.RegexParsers:
  import scala.util.parsing.combinator._
  def groups: Parser[Rules] = rep(group) ^^ (_.toMap)
  def group = kind ~ "{" ~ rep(rule) ~ "}" ^^ {
    case k ~ _ ~ matches ~ _ => k -> matches
  }
  def kind = """\w+""".r | "*"
  def rule: Parser[(String, ValueMatcher | KeyExtractor)] = """[^:\s]+""".r ~ ":" ~ valueMatcher ^^ { case p ~ _ ~ vm => p -> vm }
  def valueMatcher = always | exact | ref | key
  def always = "always" ^^ { _ => ValueMatcher.always}
  def exact = "exact" ~ "(" ~ (("""\d+""".r ^^ {_.toInt}) | ("""\{\}""".r ^^ {_ => new java.util.HashMap()}) | ("""\[\]""".r ^^ {_ => new java.util.ArrayList()}) | ("""[^\s()]*""".r)) ~ ")" ^^ {
    case _ ~ _ ~ param ~ _ => ValueMatcher.exact(param)
  }
  def ref = "ref" ~ "(" ~ """[^\s()]+""".r ~ ")" ^^ {
    case _ ~ _ ~ param ~ _ => ValueMatcher.ref(param)
  }
  def key = "key" ~ "(" ~ """[^\s()]+""".r ~ ")" ^^ {
    case _ ~ _ ~ param ~ _ => ValueMatcher.key(param)
  }
  def parse0(s: String): Rules = parseAll(groups, s) match
    case Success(matched, _) => matched
    case Failure(msg, _) =>
      println(msg)
      throw new Exception
    case Error(msg, _) =>
      println(msg)
      throw new Exception
  def parseAndMerge(s: Seq[String]): Rules =
    val result: mutable.Map[String, List[(String, ValueMatcher | KeyExtractor)]] = mutable.Map()
    s.map(parse0).foreach: i =>
      i.foreach:
        case (k, v) =>
          if result.contains(k) then
            result.update(k, result(k).++(v))
          else result.put(k, v)
    result.toMap
  end parseAndMerge
end IgnoreRulesParser 

type Rules = Map[String, List[(String, ValueMatcher | KeyExtractor)]]

object Models:
  case class DocID(id: String, `tpe`: String = "Yaml Doc"):
    override def toString: String = s"$tpe $id"
  class GVK(val kind: String, val name: String, val namespace: String) extends DocID(s"$kind/$name${if(namespace.isEmpty)""else s".$namespace"}", "K8s Resource")
  case class YamlDoc(yaml: String, tree: JsonNode, id: DocID)

  trait Changes:
    def sorting: String
  case class NewResource(doc: YamlDoc, show: Boolean) extends Changes:
    import c._, doc.id
    override def toString = s"---\n$ADD# New $id${if show then s"\n${doc.yaml}" else ""}$RESET"
    override def sorting = s"3$id"
  case class RemovedResource(doc: YamlDoc, show: Boolean) extends Changes:
    import c._, doc.id
    override def toString = s"---\n$DELETE# Removed $id${if show then s"\n${doc.yaml}" else ""}$RESET"
    override def sorting = s"2$id"
  case class DiffResource(id: DocID, changes: ObjectNode, show: Boolean) extends Changes:
    override def toString =
      s"---\n# Diffs in $id${if show then s"\n${new ObjectMapper(yamlFactory).writeValueAsString(changes)}" else ""}"
    end toString
    override def sorting = s"1$id"
  val om = new ObjectMapper()
  case class DiffResourceJsonPatch(id: DocID, tree: JsonNode, patch: List[JsonNode]) extends Changes:
    def doToString =
      val d = id.asInstanceOf[Models.GVK]
      patch.map: node =>
        node.get("path").textValue() match
          case path @ rr"/spec/template/spec/containers/$c/env/$env/name" => s"# Ignored env: ${node.get("value")}"
          case path @ rr"/spec/template/spec/containers/$c/env/$env/value" =>
            //val name = com.flipkart.zjsonpatch.JsonPointer.parse(s"/spec/template/spec/containers/$c/env/$env/name").evaluate(tree).textValue()
            val name = tree.get("spec").get("template").get("spec").get("containers").get(c.toInt).get("env").get(env.toInt).get("name").textValue()
            val containerName = tree.get("spec").get("template").get("spec").get("containers").get(c.toInt).get("name").textValue()
            if patch.exists(_.get("path").textValue().equals(path.replaceAll("/value$", "/name"))) then
              s"# Ignored env: $name"
            else
              s"kubectl -n ${d.namespace} set env ${d.kind} ${d.name} --containers=$containerName $name='${node.get("value").textValue()}'"
          case path @ rr"/spec/template/spec/containers/$c/image" =>
            //val name = com.flipkart.zjsonpatch.JsonPointer.parse(s"/spec/template/spec/containers/$c/env/$env/name").evaluate(tree).textValue()
            val name = tree.get("spec").get("template").get("spec").get("containers").get(c.toInt).get("image").textValue()
            val containerName = tree.get("spec").get("template").get("spec").get("containers").get(c.toInt).get("name").textValue()
            s"kubectl -n ${d.namespace} set image ${d.kind} ${d.name} $containerName=${node.get("value").textValue()}"
          case _ => s"kubectl -n ${d.namespace} patch ${d.kind} ${d.name} --type=json -p='[${om.writerWithDefaultPrettyPrinter().writeValueAsString(node)}]'"
      .mkString("\n")
    end doToString
    override def toString = s"---\n# Patch command for $id\n" + (id match
      case id: GVK if id.kind == "ConfigMap" => "# ConfigMap is skipped."
      case _: GVK => doToString
      case _ => "# Only k8s resources are supported"
    )
    end toString
    override def sorting = s"1$id"

object YamlDocs:
  import Models.{DocID, GVK, YamlDoc}
  trait SourceDatabase:
    def get(id: DocID)(using ReadOption): Option[YamlDoc]
  object FromK8s extends SourceDatabase:
    def get(id: DocID)(using ReadOption): Option[YamlDoc] = id match
      case gvk: GVK => get(gvk)
      case _ => None
    def get(gvk: GVK)(using ReadOption): Option[YamlDoc] =
      import gvk._
      val cmd = s"${summon[ReadOption].kubectlCmd} get $kind -oyaml $name ${if (namespace.isEmpty) "" else s"-n $namespace"}"
      val result = bash(cmd).!!!
      if result.exitCode == 0 then
        YamlDocs.read(result.out.text(), true, None)
      else if result.err.text().contains("(NotFound)") || result.err.text().contains("doesn't have a resource type") then
        None
      else
        throw new Exception(s"Error executing kubectl cmd '$cmd'.\n${result.err.text()}")
    end get

  class Static(src: => String, isK8s: => Boolean) extends SourceDatabase:
    var _sobj: MapView[DocID, YamlDoc] = _
    def sourceObjs(using ReadOption) =
      if _sobj == null then
        _sobj = src.split("(\n|^)---\\s*(\n|$)")
          .zipWithIndex
          .flatMap:
            case (y, i) => YamlDocs.read(y, isK8s, Some(i))
          .groupBy(_.id)
          .view
          .mapValues(_.head)
      _sobj
    def get(id: DocID)(using ReadOption) = sourceObjs.get(id)

  def removeTrailingSpaces(node: JsonNode): Unit =
    node match
      case node: ObjectNode =>
        node.fields().asScala
          .foreach: kv =>
            kv.getValue match
              case v: TextNode if v.textValue().contains("\n") =>
                val newText = " +\n".r.replaceSomeIn(v.textValue(), m => Some(m.group(0).replaceAll(" ", c.TRAILING_SPACE)))
                node.put(kv.getKey, JsonNodeFactory.instance.textNode(newText))
              case v: ObjectNode =>
                removeTrailingSpaces(v)
                None
              case _ =>
      case _ =>
  end removeTrailingSpaces

  def expandTextToYaml(node: JsonNode): Unit =
    node match
      case node: ObjectNode =>
        node.fields().asScala
          .flatMap: kv =>
            kv.getValue match
              case v: TextNode =>
                Try(new ObjectMapper(yamlFactory).readTree(v.textValue())).toOption
                  .filter(_.isObject)
                  .map: o =>
                    (kv.getKey, o)
              case v: ObjectNode =>
                expandTextToYaml(v)
                None
              case _ => None
          .foreach:
            case (k, v) => node.put(k, v)
      case _ =>
  end expandTextToYaml

  val pathMatcher = (new AntPathMatcher.Builder).build()
  def arrayToObj(path: String, node: JsonNode, rules: List[(String, KeyExtractor)]): JsonNode =
    node match
      case node: ArrayNode =>
        val res = JsonNodeFactory.instance.objectNode()
        val extractor = rules.find(t => pathMatcher.isMatch(t._1, path)).map(_._2)
        node.asScala.zipWithIndex.foreach: tp =>
          val sub = arrayToObj(path + "/" + tp._2, tp._1, rules)
          extractor
            .flatMap: e =>
              e.extract(tp._1, path)
            .map(k => res.put(k, sub))
            .getOrElse(res.put(tp._2+"", sub))
        res
      case node: ObjectNode =>
        node.fields().asScala.map(_.getKey).toList.foreach: k =>
          node.put(k, arrayToObj(path + "/" + k, node.get(k), rules))
        node
      case _ => node
  end arrayToObj

  def removeIgnoredFields(root: JsonNode, node: JsonNode, path: String, rules: List[(String, ValueMatcher)])(using args: ReadOption): Boolean =
    val shouldIgnore = rules.exists:
      case pt -> matcher => pathMatcher.isMatch(pt, path) && matcher.matches(root, path, node)
    node match
      case _ if shouldIgnore =>
        if args.printIgnores then
          println(s"$path: $node")
        true
      case node: ObjectNode =>
        var hasModified = false
        node.fields().asScala
          .filter: kv =>
            removeIgnoredFields(root, kv.getValue, s"$path/${kv.getKey}", rules)
          .map(_.getKey)
          .toList
          .foreach: key =>
            node.remove(key)
            hasModified = true
        hasModified && node.isEmpty
      case node: ArrayNode =>
        node.elements().asScala.toList.zipWithIndex
          .filter:
            case (n, i) => removeIgnoredFields(root, n, s"$path/$i", rules)
          .map(_._2)
          .reverse
          .foreach: i =>
            node.remove(i)
        false
      case _ =>
        if args.printIgnores then
          println(s"$path: $node")
        false
  end removeIgnoredFields

  def read(yaml0: String, k8s: Boolean, index: Option[Int])(using args: ReadOption): Option[YamlDoc] = boundary:
    if yaml0.trim.isEmpty then break(None)
    import scala.util
    Try:
      val yaml = args.neatCmd.map(c => echo(yaml0) | bash(c) | !!).getOrElse(yaml0)
      val tree = new ObjectMapper(yamlFactory).readTree(yaml)
      if tree == null || tree.isInstanceOf[MissingNode] || tree.isInstanceOf[NullNode] then
        throw RuntimeException("EMPTY_OBJECT")
      var rules: List[(String, ValueMatcher | KeyExtractor)] = List.empty
      val id =
        if k8s then
          try
            val name = tree.get("metadata").get("name").asText
            val namespace = Try(tree.get("metadata").get("namespace").asText).toOption.getOrElse("")
            val kind = tree.get("kind").asText
            new GVK(kind, name, namespace)
          catch case e => throw new Exception("Error: field '.kind' and '.metadata.name' must be set!", e)
        else DocID(index.get.toString)
      if args.isK8s then
        val kind = tree.get("kind").asText()
        rules = args.rules.getOrElse(kind, Nil) ::: args.rules.getOrElse("*", Nil)
        removeIgnoredFields(tree, tree, "", rules.filter(_._2.isInstanceOf[ValueMatcher]).map(tp => (tp._1, tp._2.asInstanceOf[ValueMatcher])))
      if args.expandText then
        expandTextToYaml(tree)
      removeTrailingSpaces(tree)
      arrayToObj("", tree, rules.filter(_._2.isInstanceOf[KeyExtractor]).map(tp => (tp._1, tp._2.asInstanceOf[KeyExtractor])))
      Models.YamlDoc(yaml, tree, id)
    .recoverWith[YamlDoc]:
      case t: RuntimeException if t.getMessage == "EMPTY_OBJECT" =>
        util.Failure(t)
      case t =>
        try
          println("Yaml Parse Error")
          new ObjectMapper(yamlFactory).readTree(yaml0)
          println("=====ERROR====")
          t.printStackTrace()
        catch case tm =>
          println("=====ERROR====")
          tm.printStackTrace();
        println("=====YAML CONTENT====")
        println(yaml0)
        util.Failure(t)
    .toOption
  end read

class YamlDiffer(using args: DiffArgs):
  val inline = args.f.inline
  import DiffFlags._
  val DIFF_FLAGS = java.util.EnumSet.of(OMIT_MOVE_OPERATION, OMIT_COPY_OPERATION, ADD_ORIGINAL_VALUE_ON_REPLACE)
  val diffRowGenerator = DiffRowGenerator.create()
    .showInlineDiffs(inline)
    .inlineDiffByWord(inline)
    .oldTag(f => if f then c.TAG_DELETE else c.TAG_END)
    .newTag(f => if f then c.TAG_ADD else c.TAG_END)
    .mergeOriginalRevised(inline)
    .lineNormalizer(identity)
    .build()
  def generateDiffText(from: JsonNode, to: JsonNode) =
    val splitNode = (_: JsonNode) match
      case n: TextNode => n.textValue().split("\n").toList.asJava
      case n => n.toString.split("\n").toList.asJava
    diffRowGenerator.generateDiffRows(splitNode(from), splitNode(to)).asScala
      .flatMap: l =>
        import c._, l._
        import com.github.difflib.text.DiffRow.Tag
        if inline then
          val mode = getTag match
            case Tag.INSERT => MARK_ADD_LINE
            case Tag.DELETE => MARK_DELETE_LINE
            case Tag.CHANGE => MARK_MODIFY_LINE
            case Tag.EQUAL => ""
          List(s"$mode$getOldLine")
        else getTag match
          case Tag.INSERT => List(s"$MARK_ADD_LINE$TAG_ADD$getNewLine$TAG_END")
          case Tag.DELETE => List(s"$MARK_DELETE_LINE$TAG_DELETE$getOldLine$TAG_END")
          case Tag.CHANGE =>
            List(s"$MARK_DELETE_LINE$TAG_DELETE$getOldLine$TAG_END", s"$MARK_ADD_LINE$TAG_ADD$getNewLine$TAG_END")
          case Tag.EQUAL => List(getOldLine)
      .mkString("\n")
  end generateDiffText

  def doDiff: Unit =
    import Models.{NewResource, RemovedResource, DiffResource, DiffResourceJsonPatch}
    if args.debug.rules then
      println(args.rules.toString().replaceAll(", ", ",\n"))
    val targetDocs = new YamlDocs.Static(read(args.target), args.f.k8s).sourceObjs.values
    args.dump.foreach: fn =>
      println(s"Dumping resource to file $fn...")
      import Models._
      val sources: List[Either[DocID, YamlDoc]] = targetDocs.map(_.id).map(id => args.source.get(id).map(Right(_)).getOrElse(Left(id))).toList
      val linesForAbsent: List[String] = sources.flatMap(_.left.toOption).map(id => s"# Missing resource: $id")
      val linesForPresent: List[String] = sources.flatMap(_.right.toOption).map(doc => s"---\n${doc.yaml}")
      os.write.over(fn, (linesForAbsent ++ linesForPresent).mkString("\n") + "\n")
      println(s"Successfully dumpped.")
    println("analyzing...")
    targetDocs
      .flatMap: target =>
        args.source.get(target.id) match
          case None => List(NewResource(target, args.f.showNew && !args.f.onlyId))
          case Some(source) =>
            val patch = JsonDiff.asJson(source.tree, target.tree, DIFF_FLAGS)
              .asInstanceOf[ArrayNode].elements().asScala.toList
            val result = JsonNodeFactory.instance.objectNode()
            patch
              .foreach: n =>
                import c._
                val path = n.get("path").asText()
                val value = n.get("value")
                import com.fasterxml.jackson.databind.node.JsonNodeType._
                n.get("op").asText() match
                  case "remove" =>
                      setValue(result, s"$path$MARK_DELETE_FIELD", value)
                  case "add" =>
                    setValue(result, s"$path$MARK_ADD_FIELD", value)
                  case "replace" => (n.get("fromValue"), value) match
                    case (from: TextNode, to: TextNode) if List(from, to).exists(_.textValue().contains("\n")) =>
                      val diffText = generateDiffText(from, to)
                      //                    println(diffText.split("\n").map("###" + _).mkString("\n"))
                      val processed = stripMultiLineDiff(processCrossLineDiff(diffText))
                      //                    println(processed.split("\n").map("$$$" + _).mkString("\n"))
                      setValue(result, path, JsonNodeFactory.instance.textNode(processed))
                    case (from, to) if inline && from.getNodeType == to.getNodeType && !Set(ARRAY, OBJECT, POJO).contains(from.getNodeType) =>
                      val rawdiff = generateDiffText(from, to)
                      val o = rawdiff.replaceAll(s"^$MARK_DELETE_LINE|^$MARK_ADD_LINE|^$MARK_MODIFY_LINE", "")
                      val mode = rawdiff.replaceAll(s"(^$MARK_DELETE_LINE|^$MARK_ADD_LINE|^$MARK_MODIFY_LINE|).*", "$1")
                      val dir = path.split("/").dropRight(1).mkString("/")
                      val key = path.split("/").last
                      setValue(result, s"$dir/$mode$key", JsonNodeFactory.instance.textNode(o))
                    case (from, to) =>
                      setValue(result, s"$path$MARK_DELETE_FIELD", from)
                      setValue(result, s"$path$MARK_ADD_FIELD", to)
            List(result).filterNot(_.isEmpty()).map(DiffResource(target.id, _, !args.f.onlyId)) ++
            List(patch).filter(_ => args.f.jsonPatch).filterNot(_.isEmpty).map(DiffResourceJsonPatch(target.id, target.tree, _))
      .toList
      .++ {
        args.source match
          case ydb: YamlDocs.Static if args.f.removed =>
            ydb.sourceObjs
              .keySet.toSet
              .diff(targetDocs.map(_.id).toSet)
              .map(ydb.sourceObjs.apply)
              .map(doc => RemovedResource(doc, args.f.showRemoved && !args.f.onlyId))
          case _ => Nil
      }
      .sortBy(_.sorting)
      .foreach: res =>
        import c._
        var indent = 0
        var lineTemplate = " $1$2"
        res.toString.split("\n").foreach: line0 =>
          val line = line0
            .replaceAll(NUMBER_KEY, "")
            .replaceAll(TRAILING_SPACE, Matcher.quoteReplacement(org.fusesource.jansi.Ansi.ansi().bgYellow.a(" ").bgDefault.toString))
          val ind = line.replaceAll("^( *(- )?).*", "$1").length
          if line.contains(s"$MARK_DELETE_FIELD:") then
            indent = ind
            lineTemplate = s"$MINUS$$1$DELETE$$2$RESET"
          else if line.contains(s"$MARK_ADD_FIELD:") then
            indent = ind
            lineTemplate = s"$PLUS$$1$ADD$$2$RESET"
          else if ind <= indent && line.trim.nonEmpty then
            indent = 0
            lineTemplate = " $1$2"
          if args.debug.unrendered then
            println(line + "<unrendered>")
            println(s"<line-template>: $lineTemplate")
          if line != "---" then println:
            if line.isEmpty then ""
            else if lineTemplate == " $1$2" then line
              .replaceAll(s"^.?(\\s*)$MARK_DELETE_LINE(.*)", s"$MINUS$$1$$2")
              .replaceAll(s"^.?(\\s*)$MARK_ADD_LINE(.*)", s"$PLUS$$1$$2")
              .replaceAll(s"^.?(\\s*)$MARK_MODIFY_LINE(.*)", s"$TILDE$$1$$2")
              .replaceAll(TAG_DELETE, DELETE)
              .replaceAll(TAG_ADD, ADD)
              .replaceAll(TAG_END, RESET)
            else line
              .replaceAll(s"($MARK_DELETE_FIELD|$MARK_ADD_FIELD):", ":")
              .replaceAll(" ?( *)(.+)", lineTemplate)
        print(RESET)
  end doDiff

  def setValue(obj: ObjectNode, path0: String, value: JsonNode): Unit =
    val path = path0.replaceAll(s"(?<=^|/)(\\d+)(?=(${c.MARK_ADD_FIELD}|${c.MARK_DELETE_FIELD})?($$|/))", c.NUMBER_KEY + "$1")
    var cur = obj
    path.split("/").dropRight(1).drop(1)
      .foreach: pp =>
        val p = pp.unescapePath
        if cur.get(p) == null then
          cur.put(p, JsonNodeFactory.instance.objectNode())
        cur = cur.get(p).asInstanceOf[ObjectNode]
    cur.put(path.split("/").last.unescapePath, value)
  end setValue

  val MARKER_PT = java.util.regex.Pattern.compile(s"(?s)(${c.TAG_ADD}|${c.TAG_DELETE}).*?${c.TAG_END}")
  // e.g.
  // Change:
  // <MODIFY_LINE>    ...<DELETE>io/stats-job-name: "istio-mesh"
  // <MODIFY_LINE>    security.<RESET>istio.io/....
  // To:
  // <MODIFY_LINE>    ...<DELETE>io/stats-job-name: "istio-mesh"<RESET>
  // <MODIFY_LINE>    <DELETE>security.<RESET>istio.io/....
  def processCrossLineDiff(diff: String): String =
    val mth = MARKER_PT.matcher(diff)
    val b = new StringBuffer()
    while mth.find() do
      val matched = mth.group()
      val tagStart = mth.group(1)
      val replacement =
        if matched.contains("\n") then
          matched.replaceAll(s"\n(${c.MARK_MODIFY_LINE})?", s"${c.TAG_END}$$0$tagStart")
        else matched
      mth.appendReplacement(b, Matcher.quoteReplacement(replacement))
    mth.appendTail(b)
    b.toString
  end processCrossLineDiff

  def stripMultiLineDiff(diff: String)(using DiffArgs): String =
    val aroundLines = summon[DiffArgs].multiLineAroundLines
    import c._
    val lines = diff.linesWithSeparators.toList
    var hasSkipped = false
    val resultLines = mutable.ListBuffer[String]()
    val markedLines = lines.map(_.matches(s"(?s).*($MARK_DELETE_LINE|$MARK_MODIFY_LINE|$MARK_ADD_LINE).*"))
    markedLines
      .zipWithIndex
      .map:
        case (b, i) => b || markedLines.slice(i-aroundLines, i+aroundLines+1).contains(true)
      .zip(lines)
      .foreach:
        case (b, l) =>
          if b then
            resultLines.addOne(l)
            hasSkipped = false
          else if !hasSkipped then
            resultLines.addOne("<skipped...>\n")
            hasSkipped = true
    resultLines.mkString("")
  end stripMultiLineDiff

type Args = DiffArgs
class ReadOption(val kubectlCmd: String = "kubectl", val rules: Rules = Map(), val isK8s: Boolean = true, val expandText: Boolean = false, val printIgnores: Boolean = false, val neatCmd: Option[String] = None)
case class DiffArgs(source: YamlDocs.SourceDatabase = YamlDocs.FromK8s,
                target: Path = root/"dev"/"stdin",
                dump: Option[Path] = None,
                extraRuleFiles: List[String] = Nil,
                extraRules: List[String] = Nil,
                flags: Set[String] = Set("rule", "inline", "removed", "expandText"),
                debugFlags: Set[String] = Set(),
                multiLineAroundLines: Int = 8,
                override val rules: Rules = Map(),
                override val neatCmd: Option[String] = None,
                override val kubectlCmd: String = "kubectl",
               ) extends ReadOption(kubectlCmd = kubectlCmd, isK8s = flags.contains("k8s"), expandText = flags.contains("expandText"), printIgnores = debugFlags.contains("ignore"), neatCmd = neatCmd):
  class Flags(flags: Set[String]) extends Dynamic:
    def selectDynamic(name: String): Boolean = flags.contains(name)
  val debug: Flags = new Flags(debugFlags)
  val f: Flags = new Flags(flags)
  def processDefault: DiffArgs = this.copy(
    rules = IgnoreRulesParser.parseAndMerge(List(defaultIgnores).filter(_ => f.rule) ::: extraRules ::: extraRuleFiles.map(p => read(getPath(p)))),
    flags = if source.isInstanceOf[YamlDocs.FromK8s.type] then flags.+("k8s") else flags
  )

object Args:
  import org.fusesource.jansi.Ansi.{Color, ansi, Attribute}
  val param = ansi().fg(Color.CYAN).a(Attribute.INTENSITY_BOLD).toString()
  val reset = ansi().reset().toString()
  extension (p: scopt.OParser[Unit, Args])
    def flagF(f: String) = p.action((_, a) => a.asInstanceOf[DiffArgs].copy(flags = a.asInstanceOf[DiffArgs].flags.+(f)))
    def flagNoF(f: String) = p.action((_, a) => a.asInstanceOf[DiffArgs].copy(flags = a.asInstanceOf[DiffArgs].flags.-(f)))
  def flagToToken(f: String) = "-([a-z])".r.replaceSomeIn(f, m => Some(m.group(1).toUpperCase))
  import scopt.OParser
  val builder = OParser.builder[Args]
  extension (p: String) def zh(z: String): String = 
    if (System.getProperty("ydiffLang") == "zh") z
    else p
  def parser =
    import builder.{arg, _}
    OParser.sequence(
      programName("ydiff"),
      head("YAML Diff".zh("Yaml对比工具")+" YDIFF_VERSION"+param),
      help('h', "help")
        .text(reset+"Show this help.".zh("显示帮助文档")+param),
      version('v', "version")
        .text(reset+"Show version".zh("显示版本")+param),
      opt[String]('l', "lang")
        .text(reset+"Choose language(zh/en)|选择语言(zh/en)"+param)
        .valueName("<language>")
        .action: (l, a) =>
          System.setProperty("ydiffLang", l)
          System.setProperty("helpFlag", "true")
          a,
      note(""),
      cmd("diff")
        .text(reset+"The default command. Run the YAML diff".zh("(默认)运行YAML对比工具")+param)
        .children(
          opt[Unit]("k8s")
            .text(reset+"Treat yaml docs as kubernetes resources.".zh("将输入当做Kubernetes资源处理。")+param)
            .optional()
            .flagF("k8s"),
          opt[Unit]("neat")
            .text(reset+"Use kubectl neat".zh("使用kubectl neat")+param)
            .optional()
            .flagF("neat")
            .action: (_, a) =>
              a.copy(
                neatCmd = List("kubectl neat", "kube-neat").find(c => bash(c + " --help").!!!.exitCode == 0)
              ),
          opt[Unit]("json-patch")
            .text(reset+"Print kubectl patch commands(k8s only).".zh("输出kubectl patch命令(仅k8s模式)。")+param)
            .flagF("jsonPatch"),
          opt[Unit]("show-new")
            .text(reset+"Show complete yaml text of new yaml docs.".zh("完整输出新增的YAML。")+param)
            .optional()
            .flagF("showNew"),
          opt[Unit]("show-removed")
            .text(reset+"Show complete yaml text of removed yaml docs.".zh("完成输出删除的YAML。")+param)
            .optional()
            .flagF("showRemoved"),
          opt[Unit]("only-id")
            .text(reset+"Show only IDs for changed/removed/added docs".zh("对于所有的修改/删除/新增YAML, 只输出ID。")+param)
            .optional()
            .flagF("onlyId"),
          opt[Unit]("no-rule")
            .text(reset+"Don't use default ignore list(k8s only).".zh("不使用默认的规则列表(仅k8s模式)。")+param)
            .flagNoF("rule"),
          opt[Unit]("no-inline")
            .text(reset+"Show diff line by line.".zh("按行显示差异")+param)
            .flagNoF("inline"),
          opt[Unit]("no-removed")
            .text(reset+"Don't show removed resources.".zh("不显示删除的资源")+param)
            .flagNoF("removed"),
          opt[Unit]("no-expand-text")
            .text(reset+"Don't expand string to yaml".zh("不会自动将字符串展开成yaml进行对比")+param)
            .flagNoF("expandText"),
          opt[String]('k', "kubectl-cmd")
            .text(reset+"Specify the kubectl cmd".zh("指定kubectl命令")+param)
            .valueName("<cmd>")
            .optional()
            .action: (k, a) =>
              a.copy(kubectlCmd = k),
          opt[Int]('m', "multi-lines-around")
            .text(reset+"How many lines should be printed before and after\nthe diff line in multi-line string".zh("跨行字符串中, 差异文本上下保留的行数。")+param)
            .valueName("<lines>")
            .optional()
            .action: (l, a) =>
              a.copy(multiLineAroundLines = l),
          opt[String]("extra-rules")
            .text(reset+"Extra rules, can be specified multiple times.".zh("额外的过滤规则, 可多次指定(仅k8s模式)。")+param)
            .valueName("<rule-text>")
            .optional()
            .action: (i, a) =>
              a.copy(extraRules = a.extraRules.appended(i)),
          opt[String]("extra-rule-file")
            .text(reset+"Extra rules file, can be specified multiple times.".zh("额外的过滤规则文件, 可多次指定(仅k8s模式)。")+param)
            .valueName("<file>")
            .optional()
            .action: (p, a) =>
              a.copy(extraRuleFiles = a.extraRuleFiles.appended(p)),
          opt[String]("dump")
            .text(reset+"Dump file name, if set, the resource of k8s source will be dumped to the file".zh("备份的文件名, 设置后将备份导出源yaml(仅k8s模式)。")+param)
            .valueName("<file>")
            .optional()
            .action: (p, a) =>
              a.copy(dump = Some(getPath(p))),
          opt[String]('d', "debug")
            .hidden()
            .action: (d, a) =>
              a.copy(debugFlags = a.debugFlags.+(flagToToken(d))),
          arg[String]("source-file")
            .text(reset+"Source yaml file, specify \"k8s\" to fetch resource\nfrom kubernetes cluster, and default to be k8s.".zh("来源文件名, 用k8s表示取自k8s集群, 默认为k8s。")+param)
            .optional()
            .action: (f, a) =>
              val docs =
                if f == "k8s" then YamlDocs.FromK8s
                else new YamlDocs.Static(read(getPath(f)), a.f.k8s)
              a.copy(source = docs),
          arg[String]("target-file")
            .text(reset+"Target yaml file, default to be stdin.".zh("目标文件名, 默认为标准输入。")+param)
            .optional()
            .action: (f, a) =>
              a.copy(target = if f.equals("-") then root/"dev"/"stdin" else getPath(f)),
        ),
      note(""),
      cmd("kubectl")
        .text("kubectl命令行模拟, 详见: ydiff kubectl --help")
        .children(
          help('h', "help")
            .text(reset+"Show kubectl help".zh("显示ydiff kubectl帮助文档")+param)
        ),
      note(reset),
      checkConfig:
        case a: DiffArgs if !a.source.isInstanceOf[YamlDocs.FromK8s.type] && a.dump.nonEmpty => failure("Dump is only available for k8s source")
        case a: DiffArgs if a.flags.contains("neat") && a.neatCmd.isEmpty => failure("Kubectl neat is not installed.")
        case _ => success
    )
  val kubectlHelp = reset+"Some utilities for maintaining k8s resources.".zh {
          import org.fusesource.jansi.Ansi.ansi
          ansi().render(
          """|@|bold,cyan ydiff kubectl|@
             |kubectl命令行模拟, 当前支持kubectl的replace(apply), get, create, delete, set image, scale命令, 以及ydiff专门的用于数据源操作的restore、print命令。执行前需要先指定YDIFF_KUBECTL_SOURCE环境变量, 来指定数据源yaml文件。
             |
             |@|bold,cyan 使用步骤|@
             |  1. 配置别名: alias kubectl="ydiff kubectl"
             |  2. 配置数据源: export YDIFF_KUBECTL_SOURCE=/the/path/to.yaml # 该文件不会被修改, 请放心使用
             |  3. 执行restore: kubectl restore # 根据配置的数据源重置数据, 保存的文件名: 原文件名追加.modify后缀
             |  4. 执行一系列kubectl命令(请留意注意事项)
             |  5. 得到结果: kubectl print 
             |
             |@|bold,cyan 样例|@
             |  alias kubectl="ydiff kubectl"
             |  export YDIFF_KUBECTL_SOURCE=/tmp/my-file.yaml
             |  kubectl restore
             |  kubectl delete ClusterRole xxx
             |  kubectl delete ConfigMap my-cm -n ns1
             |  kubectl apply -f test.yaml
             |  if ! kubectl get Deployment xxx -n xxx; then
             |    kubectl create -f xxx
             |  fi
             |  kubectl print # 输出最终的结果
             |  # 或者, 也可以执行以下命令, 将结果与另外的yaml进行对比:
             |  ydiff --k8s <(kubectl print) another-file.yaml 
             |
             |@|bold,red 注意事项|@
             |  1. 不保证与kubectl的行为一致, @|red 本工具无法替代真实的kubectl命令演练|@
             |  2. 资源类型必须指定为完整的kind, 例如对于Service, 需要指定为@|red Service, 而不是svc、service、services|@, 如kubectl get Service xxx -n xxx
             |  3. apply命令实际与replace的行为一致, 即只执行完整资源替换, 不做内容合并, 这一点@|red 与原生kubectl apply有一定差异|@
             |  4. get命令只支持yaml和默认格式, 默认返回的结果不会与kubectl原生的相同, 但可以保证资源存在时退出码返回0, 不存在时返回1(可用于脚本中if判断)
             |""".stripMargin).toString
        }+param
  // end val

System.setProperty("ydiffLang", if Option(System.getenv("LANG")).exists(_.toUpperCase().contains("CN")) then "zh" else "en")
scopt.OParser.runParser(Args.parser, args, DiffArgs()) // 仅用于根据--lang/-l选项设置帮助语言
val processedArgs =
  val result = ListBuffer.from(args)
  if Set("diff", "kubectl").intersect(args.headOption.toSet).isEmpty then
    result.insert(0, "diff")
  if (args.contains("--lang") || args.contains("-l")) && !args.contains("-h") && !args.contains("-v") then
    result.append("-h")
  result.toList

if args.headOption.contains("kubectl") then
  if args.tail.headOption.forall(o => o == "-h" || o == "--help") then
    println(Args.kubectlHelp)
  else
    KubectlExec.kubectl(args.toList.drop(1))
else
  // 这里的做法是为了解决一个问题: 显示版本(-v)的时候, 会将终端改为彩色的状态,
  // 影响后续命令行使用, 所以需要在退出前打印一下reset
  val esetup = new scopt.DefaultOEffectSetup with scopt.OEffectSetup:
    override def terminate(exitState: Either[String, Unit]): Unit =
      print(org.fusesource.jansi.Ansi.ansi().reset())
      super.terminate(exitState)
  scopt.OParser.parse(Args.parser, processedArgs, DiffArgs(), esetup) match
    case Some(a: DiffArgs) => new YamlDiffer(using a.processDefault).doDiff
    case _ =>
