#!/usr/bin/env -S scala-cli shebang
//> using scala 3.3.0
//> using dep com.zhranklin::scala-tricks:0.2.4
//> using dep com.flipkart.zjsonpatch:zjsonpatch:0.4.14
//> using dep com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.14.3
//> using dep com.lihaoyi::os-lib:0.8.0
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
import os.{root, read}
import os.Path
import scala.util.Try
import scala.collection.mutable.ListBuffer

val jsonMapper = new ObjectMapper

def getPath(p: String) = os.Path.expandUser(p, os.pwd)

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
  /**/checksum~1config-volume*: always
  /apiVersion: always
  /metadata/annotations/autoscaling.alpha.kubernetes.io/conditions: always
  /metadata/annotations/autoscaling.alpha.kubernetes.io/current-metrics: always
  /metadata/annotations/deployment.kubernetes.io/revision: always
  /metadata/annotations/kubectl.kubernetes.io/last-applied-configuration: always
  /metadata/creationTimestamp: always
  /metadata/finalizers: always
  /metadata/generation: always
  /metadata/labels/release: always
  /metadata/managedFields: always
  /metadata/resourceVersion: always
  /metadata/selfLink: always
  /metadata/uid: always
  /spec/selector/matchLabels/release: always
  /spec/template/metadata/creationTimestamp: always
  /spec/template/metadata/generation: always
  /spec/template/metadata/labels/release: always
  /spec/template/metadata/resourceVersion: always
  /spec/template/metadata/selfLink: always
  /spec/template/metadata/uid: always
  /status: always
  /webhooks/*/clientConfig/caBundle: always
  /spec/template/spec/containers/*/env: key(/name)
  /spec/template/spec/containers/*/volumeMounts: key(/name)
  /spec/template/spec/volumes: key(/name)
}
Deployment {
  /spec/progressDeadlineSeconds: exact(600)
  /spec/revisionHistoryLimit: exact(10)
  /spec/strategy/type: exact(RollingUpdate)
  /spec/template/spec/serviceAccount: ref(../serviceAccountName)
  /spec/template/spec/containers/*/imagePullPolicy: exact(IfNotPresent)
  /spec/template/spec/containers/*/ports/*/protocol: exact(TCP)
  /spec/template/spec/containers/*/*Probe/failureThreshold: exact(3)
  /spec/template/spec/containers/*/*Probe/periodSeconds: exact(3)
  /spec/template/spec/containers/*/*Probe/timeoutSeconds: exact(3)
  /spec/template/spec/containers/*/*Probe/http*/scheme: exact(HTTP)
  /spec/template/spec/containers/*/*/successThreshold: exact(1)
  /spec/template/spec/containers/*/terminationMessagePath: exact(/dev/termination-log)
  /spec/template/spec/containers/*/terminationMessagePolicy: exact(File)
  /spec/template/spec/dnsPolicy: exact(ClusterFirst)
  /spec/template/spec/restartPolicy: exact(Always)
  /spec/template/spec/schedulerName: exact(default-scheduler)
  /spec/template/spec/securityContext: exact([])
  /spec/template/spec/terminationGracePeriodSeconds: exact(30)
  /spec/template/spec/volumes/*/*/defaultMode: exact(420)
  /metadata/annotations/deployment.kubernetes.io~1revision: always
}
Service {
  /spec/clusterIP: always
  /spec/ports/*/protocol: exact(TCP)
  /spec/sessionAffinity: exact(None)
  /spec/type: exact(ClusterIP)
  /spec/ports/*/targetPort: ref(../port)
}
ServiceAccount {
  /secrets: always
}
MutatingWebhookConfiguration {
  /webhooks/*/clientConfig/service/port: exact(443)
  /webhooks/*/matchPolicy: exact(Exact)
  /webhooks/*/objectSelector: exact([])
  /webhooks/*/reinvocationPolicy: exact(Never)
  /webhooks/*/rules/*/scope: exact(*)
  /webhooks/*/timeoutSeconds: exact(30)
}
"""

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
  def exact = "exact" ~ "(" ~ (("""\d+""".r ^^ {_.toInt}) | ("""[]""" ^^ {_ => new java.util.HashMap()}) | ("""[^\s()]*""".r)) ~ ")" ^^ {
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
    def get(id: DocID)(using args: Args): Option[YamlDoc]
  object FromK8s extends SourceDatabase:
    def get(id: DocID)(using args: Args): Option[YamlDoc] = id match
      case gvk: GVK => get(gvk)
      case _ => None
    def get(gvk: GVK)(using args: Args): Option[YamlDoc] =
      import gvk._
      val result = bash(s"kubectl get $kind -oyaml $name ${if (namespace.isEmpty) "" else s"-n $namespace"}").!!!
      if result.exitCode != 1 && result.err.text().contains("(NotFound)") || result.err.text().contains("doesn't have a resource type") then
        None
      else
        YamlDocs.read(result.out.text(), true, None)
    end get

  class Static(src: => String, isK8s: => Boolean) extends SourceDatabase:
    var _sobj: MapView[DocID, YamlDoc] = _
    def sourceObjs(using args: Args) =
      if _sobj == null then
        _sobj = src.split("(\n|^)---\\s*(\n|$)")
          .zipWithIndex
          .flatMap:
            case (y, i) => YamlDocs.read(y, isK8s, Some(i))
          .groupBy(_.id)
          .view
          .mapValues(_.head)
      _sobj
    def get(id: DocID)(using args: Args) = sourceObjs.get(id)

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

  def removeIgnoredFields(root: JsonNode, node: JsonNode, path: String, rules: List[(String, ValueMatcher)])(using args: Args): Boolean =
    val shouldIgnore = rules.exists:
      case pt -> matcher => pathMatcher.isMatch(pt, path) && matcher.matches(root, path, node)
    node match
      case _ if shouldIgnore =>
        if args.debug.ignore then
          println(s"$path: $node")
        true
      case node: ObjectNode =>
        node.fields().asScala
          .filter: kv =>
            removeIgnoredFields(root, kv.getValue, s"$path/${kv.getKey}", rules)
          .map(_.getKey)
          .toList
          .foreach: key =>
            node.remove(key)
        false
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
        if args.debug.ignore then
          println(s"$path: $node")
        false
  end removeIgnoredFields

  def read(yaml: String, k8s: Boolean, index: Option[Int])(using args: Args): Option[YamlDoc] = boundary:
    if yaml.trim.isEmpty then break(None)
    import scala.util
    Try:
      val tree = new ObjectMapper(yamlFactory).readTree(yaml)
      if tree == null || tree.isInstanceOf[MissingNode] || tree.isInstanceOf[NullNode] then
        throw RuntimeException("EMPTY_OBJECT")
      var rules: List[(String, ValueMatcher | KeyExtractor)] = List.empty
      if args.f.k8s then
        val kind = tree.get("kind").asText()
        rules = args.rules.getOrElse(kind, Nil) ::: args.rules.getOrElse("*", Nil)
        removeIgnoredFields(tree, tree, "", rules.filter(_._2.isInstanceOf[ValueMatcher]).map(tp => (tp._1, tp._2.asInstanceOf[ValueMatcher])))
      if args.f.expandText then
        expandTextToYaml(tree)
      removeTrailingSpaces(tree)
      arrayToObj("", tree, rules.filter(_._2.isInstanceOf[KeyExtractor]).map(tp => (tp._1, tp._2.asInstanceOf[KeyExtractor])))
      val id =
        if k8s then
          val name = tree.get("metadata").get("name").asText
          val namespace = Try(tree.get("metadata").get("namespace").asText).toOption.getOrElse("")
          val kind = tree.get("kind").asText
          new GVK(kind, name, namespace)
        else DocID(index.get.toString)
      Models.YamlDoc(yaml, tree, id)
    .recoverWith[YamlDoc]:
      case t: RuntimeException if t.getMessage == "EMPTY_OBJECT" =>
        util.Failure(t)
      case t =>
        try
          new ObjectMapper(yamlFactory).readTree(yaml)
        catch
          case t =>
            println(yaml)
            t.printStackTrace();
        util.Failure(t)
    .toOption
  end read

class YamlDiffer(using args: Args):
  val inline = !args.f.noInline
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
          case ydb: YamlDocs.Static =>
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

  def stripMultiLineDiff(diff: String)(using args: Args): String =
    val aroundLines = args.multiLineAroundLines
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

case class Args(source: YamlDocs.SourceDatabase = YamlDocs.FromK8s,
                target: Path = root/"dev"/"stdin",
                dump: Option[Path] = None,
                extraRuleFiles: List[String] = Nil,
                extraRules: List[String] = Nil,
                flags: Set[String] = Set(),
                debugFlags: Set[String] = Set(),
                multiLineAroundLines: Int = 8,
                rules: Rules = Map(),
                mergeFiles: List[Path] = Nil,
               ):
  class Flags(flags: Set[String]) extends Dynamic:
    def selectDynamic(name: String): Boolean = flags.contains(name)
  val debug: Flags = new Flags(debugFlags)
  val f: Flags = new Flags(flags)
  def processDefault: Args = this.copy(
    rules = IgnoreRulesParser.parseAndMerge(List(defaultIgnores).filterNot(_ => f.noIgnore) ::: extraRules ::: extraRuleFiles.map(p => read(getPath(p)))),
    flags = if source.isInstanceOf[YamlDocs.FromK8s.type] then flags.+("k8s") else flags
  )

object Args:
  import org.fusesource.jansi.Ansi.{Color, ansi, Attribute}
  val param = ansi().fg(Color.CYAN).a(Attribute.INTENSITY_BOLD).toString()
  val reset = ansi().reset().toString()
  extension (p: scopt.OParser[Unit, Args]) def flagF(f: String) = p.action((_, a) => a.copy(flags = a.flags.+(flagToToken(f))))
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
          opt[Unit]("no-ignore")
            .text(reset+"Don't use default ignore list(k8s only).".zh("不使用默认的规则列表(仅k8s模式)。")+param)
            .flagF("noIgnore"),
          opt[Unit]("no-inline")
            .text(reset+"Show diff line by line."+param)
            .flagF("noInline"),
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
      cmd("merge")
        .text(reset+"Merge kubernetes resource files. ".zh("合并Kubernetes资源文件。")+ansi().fg(Color.RED).a(Attribute.INTENSITY_BOLD)
          + "NOTICE: Resource with same ID will be replaced. The content will not be merged.".zh("注意: ID相同的资源会直接进行替换, 而不是合并")+reset+param)
        .flagF("merge")
        .flagF("k8s")
        .children(
          arg[String]("<files>...")
            .text(reset+"File list to be merged".zh("需要合并的文件列表")+param)
            .required()
            .optional()
            .minOccurs(1)
            .unbounded()
            .action: (f, a) =>
              val file = if f.equals("-") then root/"dev"/"stdin" else getPath(f)
              a.copy(mergeFiles = a.mergeFiles.appended(file)),
        ),
      note(reset),
      checkConfig:
        case a if !a.source.isInstanceOf[YamlDocs.FromK8s.type] && a.dump.nonEmpty => failure("Dump is only available for k8s source")
        case _ => success
    )
  // end val
def doMerge(using a: Args) =
      import Models._
      a.mergeFiles
        .map: f =>
          new YamlDocs.Static(read(f), true).sourceObjs.toMap
        .reduce(_++_)
        .values
        .foreach: doc =>
          println("---")
          println(doc.yaml)
System.setProperty("ydiffLang", if Option(System.getenv("LANG")).exists(_.toUpperCase().contains("CN")) then "zh" else "en")
scopt.OParser.runParser(Args.parser, args, Args()) // 仅用于根据--lang/-l选项设置帮助语言
val processedArgs =
  val result = ListBuffer.from(args)
  if Set("diff", "merge").intersect(args.headOption.toSet).isEmpty then
    result.insert(0, "diff")
  if (args.contains("--lang") || args.contains("-l")) && !args.contains("-h") && !args.contains("-v") then
    result.append("-h")
  result.toList
scopt.OParser.parse(Args.parser, processedArgs, Args()) match
  case Some(a) =>
    given Args = a.processDefault
    if summon[Args].f.merge then
      doMerge
    else
      new YamlDiffer().doDiff
  case _ =>
