package nextflow.validation

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import groovyx.gpars.dataflow.DataflowReadChannel
import groovyx.gpars.dataflow.DataflowWriteChannel
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import org.yaml.snakeyaml.Yaml

import nextflow.Channel
import nextflow.Global
import nextflow.Nextflow
import nextflow.plugin.extension.Function
import nextflow.Session


@Slf4j
@CompileStatic
class SamplesheetConverter {

    private static List<String> errors = []
    private static List<String> warnings = []

    static boolean hasErrors() { errors.size()>0 }
    static List<String> getErrors() { errors }

    static boolean hasWarnings() { warnings.size()>0 }
    static List<String> getWarnings() { warnings }

    static List convertToList(
        Path samplesheetFile, 
        Path schemaFile
        ) {

        def Map schema = (Map) new JsonSlurper().parseText(schemaFile.text)
        def Map<String, Map<String, String>> schemaFields = schema["properties"]
        def Set<String> allFields = schemaFields.keySet()
        def List<String> requiredFields = schema["required"]

        def String fileType = _getFileType(samplesheetFile)
        def String delimiter = fileType == "csv" ? "," : fileType == "tsv" ? "\t" : null
        def List<Map<String,String>> samplesheetList

        if(fileType == "yaml"){
            samplesheetList = new Yaml().load((samplesheetFile.text))
        }
        else {
            Path fileSamplesheet = Nextflow.file(samplesheetFile) as Path
            samplesheetList = fileSamplesheet.splitCsv(header:true, strip:true, sep:delimiter)
        }

        // Field checks + returning the channels
        def Map<String,List<String>> uniques = [:]
        def Boolean headerCheck = true
        def Integer sampleCount = 0
        
        def List outputs = samplesheetList.collect { Map row ->
            def Set rowKeys = row.keySet()
            def Set differences = allFields - rowKeys
            def String yamlInfo = fileType == "yaml" ? " for sample ${sampleCount}." : ""

            def unexpectedFields = rowKeys - allFields
            if(unexpectedFields.size() > 0) {
                this.errors << "[Samplesheet Error] The samplesheet contains following unwanted field(s): ${unexpectedFields}${yamlInfo}".toString()
            }

            def List<String> missingFields = requiredFields - rowKeys
            if(missingFields.size() > 0) {
                this.errors << "[Samplesheet Error] The samplesheet requires '${requiredFields.join(",")}' as header field(s), but is missing these: ${missingFields}${yamlInfo}".toString()
            }

            // Check required dependencies
            def Map dependencies = schema["dependentRequired"]
            if(dependencies) {
                for( dependency in dependencies ){
                    if(row[dependency.key] != "" && row[dependency.key]) {
                        def List<String> missingValues = []
                        for( String value in dependency.value ){
                            if(row[value] == "" || !(row[value])) {
                                missingValues.add(value)
                            }
                        }
                        if (missingValues) {
                            this.errors << "[Samplesheet Error] ${dependency.value} field(s) should be defined when '${dependency.key}' is specified, but  the field(s) ${missingValues} are/is not defined.".toString()
                        }
                    }
                }
            }

            def Map meta = [:]
            def ArrayList output = []

            for( Map.Entry<String, Map> field : schemaFields ){
                def String key = field.key
                def String regexPattern = field['value']['pattern'] && field['value']['pattern'] != '' ? field['value']['pattern'] : '^.*$'
                def String metaNames = field['value']['meta']

                def String input = row[key]

                if((input == null || input == "") && key in requiredFields){
                    this.errors << "[Samplesheet Error] Sample ${sampleCount} does not contain an input for required field '${key}'.".toString()
                }
                else if(!(input ==~ regexPattern) && input != '' && input) {
                    this.errors << "[Samplesheet Error] The '${key}' value for sample ${sampleCount} does not match the pattern '${regexPattern}'.".toString()
                }
                else if(field['value']['unique']){
                    if(!(key in uniques)){
                        uniques[key] = []
                    }
                    if(input in uniques[key] && input){
                        this.errors << "[Samplesheet Error] The '${key}' value needs to be unique. '${input}' was found twice in the samplesheet.".toString()
                    }
                    uniques[key].add(input)
                }

                if(metaNames) {
                    for(name : metaNames.tokenize(',')) {
                        meta[name] = (input != '' && input) ? 
                                _checkAndTransform(input, field, sampleCount) : 
                            field['value']['default'] ? 
                                _checkAndTransform(field['value']['default'] as String, field, sampleCount) : 
                                null
                    }
                }
                else {
                    def inputFile = (input != '' && input) ? 
                            _checkAndTransform(input, field, sampleCount) : 
                        field['value']['default'] ? 
                            _checkAndTransform(field['value']['default'] as String, field, sampleCount) : 
                            []
                    output.add(inputFile)
                }
            }
            output.add(0, meta)
            return output
        }

        if (this.hasErrors()) {
            String message = "" + this.getErrors().join("\n")
            throw new SchemaValidationException(message, this.getErrors())
        }

        return outputs
    }

    // Function to infer the file type of the samplesheet
    private static String _getFileType(
        Path samplesheetFile
    ) {
        def String extension = samplesheetFile.getExtension()
        if (extension in ["csv", "tsv", "yml", "yaml"]) {
            return extension == "yml" ? "yaml" : extension
        }

        def String header = _getHeader(samplesheetFile)

        def Integer commaCount = header.count(",")
        def Integer tabCount = header.count("\t")

        if ( commaCount == tabCount ){
            this.errors << "[Samplesheet Error] Could not derive file type from ${samplesheetFile}. Please specify the file extension (CSV, TSV, YML and YAML are supported).".toString()
        }
        if ( commaCount > tabCount ){
            return "csv"
        }
        else {
            return "tsv"
        }
    }

    // Function to get the header from a CSV or TSV file
    private static String _getHeader(
        Path samplesheetFile
    ) {
        def String header
        samplesheetFile.withReader { header = it.readLine() }
        return header
    }

    // Function to check and transform an input field from the samplesheet
    private static _checkAndTransform(
        String input,
        Map.Entry<String, Map> field,
        Integer sampleCount
    ) {
        def String type = field['value']['type']
        def String format = field['value']['format']
        def String key = field.key

        def List<String> supportedTypes = ["string", "integer", "boolean"]
        if(!(type in supportedTypes)) {
            this.errors << "[Samplesheet Schema Error] The type '${type}' specified for ${key} is not supported. Please specify one of these instead: ${supportedTypes}".toString()
        }

        if(type == "string" || !type) {
            List<String> supportedFormats = ["file-path", "directory-path"]
            if(!(format in supportedFormats) && format) {
                this.errors << "[Samplesheet Schema Error] The string format '${format}' specified for ${key} is not supported. Please specify one of these instead: ${supportedFormats} or don't supply a format for a simple string.".toString()
            }
            if(format == "file-path" || format =="directory-path") {
                def Path inputFile = Nextflow.file(input) as Path
                if(!inputFile.exists()){
                    this.errors << "[Samplesheet Error] The '${key}' file or directory (${input}) for sample ${sampleCount} does not exist.".toString()
                }
                return inputFile
            }
            else {
                return input as String
            }
        }
        else if(type == "integer") {
            try {
                return input as Integer
            } catch(java.lang.NumberFormatException e) {
                this.errors << "[Samplesheet Error] The '${key}' value (${input}) for sample ${sampleCount} is not a valid integer.".toString()
            }
        }
        else if(type == "boolean") {
            if(input.toLowerCase() == "true") {
                return true
            }
            else if(input.toLowerCase() == "false") {
                return false
            }
            else {
                this.errors << "[Samplesheet Error] The '${key}' value (${input}) for sample ${sampleCount} is not a valid boolean.".toString()
            }
        }
    }
}