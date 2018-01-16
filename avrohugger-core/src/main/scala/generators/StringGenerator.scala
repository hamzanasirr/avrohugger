package avrohugger
package generators

import avrohugger.format.abstractions.SourceFormat
import avrohugger.input.DependencyInspector
import avrohugger.input.NestedSchemaExtractor
import avrohugger.input.reflectivecompilation.schemagen._
import avrohugger.input.parsers.{ FileInputParser, StringInputParser}
import avrohugger.matchers.TypeMatcher
import avrohugger.stores.{ ClassStore, SchemaStore }

import java.io.{File, FileNotFoundException, IOException}

import org.apache.avro.{ Protocol, Schema }
import org.apache.avro.Schema.Type.ENUM

// Unable to overload this class' methods because outDir uses a default value
private[avrohugger] object StringGenerator {

  def schemaToStrings(
    schema: Schema,
    format: SourceFormat,
    classStore: ClassStore,
    schemaStore: SchemaStore,
    typeMatcher: TypeMatcher,
    restrictedFields: Boolean): List[String] = {
    val maybeNamespace = DependencyInspector.getReferredNamespace(schema)
    val topLevels =
      NestedSchemaExtractor.getNestedSchemas(schema, schemaStore, typeMatcher)
    //reversed to process nested classes first
    val compilationUnits = topLevels.reverse.distinct.flatMap(schema => {
      // pass in the top-level schema's namespace if the nested schema has none
      val maybeNS = DependencyInspector.getReferredNamespace(schema) orElse {
        maybeNamespace
      }
      format.asCompilationUnits(
        classStore,
        maybeNS,
        Left(schema),
        schemaStore,
        None,
        typeMatcher,
        restrictedFields)
    })
    compilationUnits.map(compUnit => removeExtraWarning(compUnit.codeString))
  }

  def protocolToStrings(
    protocol: Protocol,
    format: SourceFormat,
    classStore: ClassStore,
    schemaStore: SchemaStore,
    typeMatcher: TypeMatcher,
    restrictedFields: Boolean): List[String] = {
    val namespace: Option[String] = Option(protocol.getNamespace)
    val compilationUnits = format.asCompilationUnits(
      classStore,
      namespace,
      Right(protocol),
      schemaStore,
      None,
      typeMatcher,
      restrictedFields)
    compilationUnits.map(compUnit => removeExtraWarning(compUnit.codeString))
  }

  def stringToStrings(
    str: String,
    format: SourceFormat,
    classStore: ClassStore,
    schemaStore: SchemaStore,
    stringParser: StringInputParser,
    typeMatcher: TypeMatcher,
    restrictedFields: Boolean): List[String] = {
    val schemaOrProtocols = stringParser.getSchemaOrProtocols(str, schemaStore)
    val codeStrings = schemaOrProtocols.flatMap(schemaOrProtocol => {
      schemaOrProtocol match {
        case Left(schema) => {
          schemaToStrings(schema, format, classStore, schemaStore, typeMatcher, restrictedFields)
        }
        case Right(protocol) => {
          protocolToStrings(protocol, format, classStore, schemaStore, typeMatcher, restrictedFields)
        }
      }
    }).distinct
    // reset the schema store after processing the whole submission
    schemaStore.schemas.clear
    codeStrings
  }

  def fileToStrings(
    inFile: File,
    format: SourceFormat,
    classStore: ClassStore,
    schemaStore: SchemaStore,
    fileParser: FileInputParser,
    typeMatcher: TypeMatcher,
    restrictedFields: Boolean): List[String] = {
    try {
      val schemaOrProtocols: List[Either[Schema, Protocol]] =
        fileParser.getSchemaOrProtocols(inFile, format, classStore)
      schemaOrProtocols.flatMap(schemaOrProtocol => schemaOrProtocol match {
        case Left(schema) => {
          schemaToStrings(schema, format, classStore, schemaStore, typeMatcher, restrictedFields)
        }
        case Right(protocol) => {
          protocolToStrings(protocol, format, classStore, schemaStore, typeMatcher, restrictedFields)
        }
      })
    }
    catch {
      case ex: FileNotFoundException => sys.error("File not found:" + ex)
      case ex: IOException => sys.error("Problem while using the file: " + ex)
    }
  }


  def removeExtraWarning(codeStr: String): String = {
    if (codeStr.startsWith("""/** MACHINE-GENERATED FROM AVRO SCHEMA. DO NOT EDIT DIRECTLY */
      |/**
      | * Autogenerated by Avro
      | *
      | * DO NOT EDIT DIRECTLY
      | */
      |""".stripMargin))
      codeStr.replace("/** MACHINE-GENERATED FROM AVRO SCHEMA. DO NOT EDIT DIRECTLY */\n", "")
    else codeStr
  }

}
