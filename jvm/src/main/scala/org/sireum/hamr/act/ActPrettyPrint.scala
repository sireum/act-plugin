// #Sireum

package org.sireum.hamr.act

import org.sireum._
import org.sireum.hamr.act.ast.{ASTObject, Assembly, BinarySemaphore, Composition, Consumes, Dataport, Direction, Emits, Instance, Method, Mutex, Procedure, Provides, Semaphore, Uses}
import org.sireum.ops.StringOps
import org.sireum.hamr.act.Util.reporter
import org.sireum.hamr.act.periodic.PeriodicDispatcherTemplate
import org.sireum.hamr.act.templates.CMakeTemplate
import org.sireum.hamr.codegen.common.symbols.SymbolTable

@record class ActPrettyPrint {

  var resources : ISZ[Resource] = ISZ()
  var rootServer: String = ""
  var actContainer: Option[ActContainer] = None[ActContainer]()

  def tempEntry(destDir: String,
                container: ActContainer,
                cFiles: ISZ[String],
                cHeaderDirectories: ISZ[String],
                aadlRootDir: String,
                hamrLibs: Map[String, HamrLib],
                platform: ActPlatform.Type,
                symbolTable: SymbolTable
               ): ISZ[Resource] = {

    rootServer = container.rootServer
    actContainer = Some(container)

    prettyPrint(container.models)

    if(Os.env("DOTTY").nonEmpty){
      val assembly = container.models(0).asInstanceOf[Assembly]
      val dot = org.sireum.hamr.act.dot.HTMLDotGenerator.dotty(assembly, F)
      add(s"graph.dot", dot)
      
      val dotSimple = org.sireum.hamr.act.dot.HTMLDotGenerator.dotty(assembly, T)
      add(s"graph_simple.dot", dotSimple)
    }

    var cmakeComponents: ISZ[ST] = container.cContainers.map((c: C_Container) => {
      var sourcePaths: ISZ[String] = ISZ()
      var includePaths: ISZ[String] = ISZ()

      if(c.sourceText.nonEmpty) {
        val dir = s"${Util.DIR_COMPONENTS}/${c.componentId}/"
        val rootDestDir = dir

        for(st <- c.sourceText) {
          val path = s"${aadlRootDir}/${st}"
          val p = Os.path(path)

          if(p.exists) {
            if(StringOps(st).endsWith(".c")) {
              val fname = s"${rootDestDir}/src/${p.name}"
              add(fname, st"""${p.read}""")

              sourcePaths = sourcePaths :+ fname

            } 
            else if(StringOps(st).endsWith(".h")) {
              val fname = s"${rootDestDir}/includes/${p.name}"
              add(fname, st"""${p.read}""")
            }
            else {
              reporter.warn(None(), Util.toolName, s"${path} does not appear to be a valid C source file")
            }
          } else {
            reporter.warn(None(), Util.toolName, s"${path} does not exist")
          }
        }
      }

      sourcePaths = sourcePaths ++ c.cSources.map((r: Resource) => r.path) ++ c.cmakeSOURCES
      includePaths = includePaths ++ c.cIncludes.map((r: Resource) => 
        Util.getDirectory(r.path)) ++ c.cmakeINCLUDES

      val hamrLib = getHamrLib(c.instanceName, hamrLibs)
      
      val hasAux = cFiles.nonEmpty && c.componentId != PeriodicDispatcherTemplate.DISPATCH_CLASSIFIER

      CMakeTemplate.cmake_DeclareCamkesComponent(
        componentName = c.componentId,
        sources = sourcePaths,
        includes = includePaths,
        libs = c.cmakeLIBS,
        hasAux = hasAux,
        hamrLib = hamrLib)
    })

    for (m <- container.monitors) {
      m match {
        case i: TB_Monitor => prettyPrint(ISZ(i.interface))
        case i: Ihor_Monitor => prettyPrint(ISZ(i.interfaceReceiver, i.interfaceSender))
      }

      val hamrLib = getHamrLib(Util.SlangTypeLibrary, hamrLibs)
      
      cmakeComponents = cmakeComponents :+ CMakeTemplate.cmake_DeclareCamkesComponent(
        componentName = m.i.component.name,
        sources = ISZ(m.cimplementation.path),
        includes = ISZ(Util.getDirectory(m.cinclude.path), Util.getTypeIncludesPath()),
        libs = ISZ(),
        hasAux = F,
        hamrLib = hamrLib)

      add(s"${m.cimplementation.path}", m.cimplementation.content)
      add(s"${m.cinclude.path}", m.cinclude.content)
      add(s"${m.cinclude.path}", m.cinclude.content)
    }

    if(container.samplingPorts.nonEmpty) {
      
      val seqNumFile = s"${Util.getTypeRootPath()}/includes/${Util.genCHeaderFilename(StringTemplate.SeqNumName)}"
      add(seqNumFile, StringTemplate.seqNumHeader())
      
      for (spi <- container.samplingPorts) {
        val header = StringTemplate.samplingPortHeader(spi)
        val impl = StringTemplate.samplingPortImpl(spi)

        add(spi.headerPath, header)
        add(spi.implPath, impl)
      }
    }
        
    var cmakeEntries: ISZ[ST] = ISZ()

    if(actContainer.get.requiresTimeServer) {
      cmakeEntries = cmakeEntries :+ st"includeGlobalComponents()"
    }

    cmakeEntries = cmakeEntries :+ CMakeTemplate.cmake_addSubDir_TypeLibrary()

    if(symbolTable.hasVM()) {
      cmakeEntries = cmakeEntries :+ CMakeTemplate.cmake_addSubDir_VM()
    }

    if(Util.hamrIntegration(platform) && hamrLibs.nonEmpty) {

      //cmakeEntries = cmakeEntries :+ StringTemplate.cmakeHamrExecuteProcess()

      for(hamrLib <- hamrLibs.values){
        
        //cmakeEntries = cmakeEntries :+ StringTemplate.cmakeHamrLib(hamrLib.instanceName, hamrLib.staticLib)

        //cmakeEntries = cmakeEntries :+ StringTemplate.cmakeHamrIncludes(hamrLib.instanceName, hamrLib.includeDirs)

        cmakeEntries = cmakeEntries :+ CMakeTemplate.cmake_add_subdirectory(s"$${CMAKE_CURRENT_LIST_DIR}/hamr/${hamrLib.instanceName}")
      }
    }
    
    if(container.connectors.nonEmpty) {
      cmakeEntries = cmakeEntries :+
        st"""# add path to connector templates
            |CAmkESAddTemplatesPath(../../../../components/templates/)
            |"""
    }

    if(cFiles.nonEmpty) {
      cmakeEntries = cmakeEntries :+ CMakeTemplate.cmakeAuxSources(cFiles, cHeaderDirectories)
    }

    cmakeEntries = cmakeEntries ++ cmakeComponents

    val cmakelist = CMakeTemplate.cmakeLists(container.rootServer, cmakeEntries)

    add("CMakeLists.txt", cmakelist)

    
    val c: ISZ[Resource] = container.cContainers.flatMap((x: C_Container) => x.cSources ++ x.cIncludes)
    val auxResources: ISZ[Resource] = container.auxFiles

    
    addExeResource("bin/run-camkes.sh", StringTemplate.runCamkesScript(symbolTable.hasVM()))
    
    // add dest dir to path
    val ret = (resources ++ c ++ auxResources).map((o: Resource) => Resource(
      path = s"${destDir}/${o.path}", 
      content = o.content,
      overwrite = o.overwrite,
      makeExecutable = o.makeExecutable
    ))
    
    return ret
  }

  def prettyPrint(objs: ISZ[ASTObject]): Unit = {
    for(a <- objs) {
      visit(a)
    }
  }

  def visit(a: ASTObject) : Option[ST] = {
    a match {
      case o: Assembly => visitAssembly(o)
      case o: Procedure => visitProcedure(o)
      case _ =>
        reporter.error(None(), Util.toolName, s"Not handling: ast object ${a}")
    }
    return None()
  }

  def visitAssembly(a: Assembly) : Option[ST] = {
    var children: ISZ[ST] = ISZ()

    val comp = visitComposition(a.composition)

    val preprocessorIncludes = actContainer.get.globalPreprocessorIncludes.map((m: String) => s"#include ${m}")

    var imports = Set.empty[String] ++ actContainer.get.globalImports.map((m: String) => s"import ${m};")
    
    imports = imports ++ resources.map((o: Resource) => s"""import "${o.path}";""")

    val connectors: ISZ[ST] = actContainer.get.connectors.map(c => {
      val fromType: ST = if(c.from_template.nonEmpty) st"""template "${c.from_template.get}"""" else st"""TODO"""
      val toType: ST = if(c.from_template.nonEmpty) st"""template "${c.to_template.get}"""" else st"""TODO"""
      st"""connector ${c.name} {
          |  from ${c.from_type.name} ${fromType};
          |  to ${c.to_type.name} ${toType};
          |}
          |"""
    })

    val st =
      st"""${StringTemplate.doNotEditComment()}
          |
          |${(preprocessorIncludes, "\n")}
          |${(imports.elements, "\n")}
          |
          |${(connectors, "\n")}
          |assembly {
          |  ${comp.get}
          |
          |  configuration {
          |    ${(a.configuration, "\n")}
          |
          |    ${(a.configurationMacros, "\n")}
          |  }
          |}
          |"""

    add(s"${rootServer}.camkes", st)

    return None()
  }

  def visitComposition(o: Composition) : Option[ST] = {
    assert(o.groups.isEmpty)
    assert(o.exports.isEmpty)

    var instances: ISZ[ST] = ISZ()
    var connections: ISZ[ST] = ISZ()

    for(i <- o.instances) {
      visitInstance(i)
      instances = instances :+ st"""component ${i.component.name} ${i.name};"""
    }

    if(actContainer.get.requiresTimeServer) {
      instances = instances :+ st"component ${PeriodicDispatcherTemplate.TIMER_SERVER_CLASSIFIER} ${PeriodicDispatcherTemplate.TIMER_INSTANCE};"
    }

    for(c <- o.connections) {
      val from = s"${c.from_ends(0).component}.${c.from_ends(0).end}"
      val to = s"${c.to_ends(0).component}.${c.to_ends(0).end}"

      connections = connections :+
        st"""connection ${c.connectionType} ${c.name}(from ${from}, to ${to});"""
    }


    val st =
      st"""composition {
          |  ${(instances, "\n")}
          |
          |  ${(connections, "\n")}
          |  ${(o.externalEntities, "\n")}
          |}"""

    /*
    println("--------")
    println(st.render)
    println("--------")
    */
    
    return Some(st)
  }

  def visitInstance(i: Instance): Option[ST] = {
    var name = i.component.name

    val st =
      st"""${(i.component.imports.map((i: String) => s"import ${i};"), "\n")}
          |${(i.component.preprocessorIncludes.map((i: String) => s"#include ${i}"), "\n")}
          |component ${name} {
          |  ${(i.component.includes.map((i: String) => s"include ${i};"), "\n")}
          |  ${if(i.component.control) "control;" else ""}
          |  ${(i.component.provides.map((p: Provides) => StringTemplate.provides(p)), "\n")}
          |  ${(i.component.uses.map((u: Uses) => StringTemplate.uses(u)), "\n")}
          |  ${(i.component.emits.map((e: Emits) => StringTemplate.emits(e)), "\n")}
          |  ${(i.component.consumes.map((c: Consumes) => StringTemplate.consumes(c)), "\n")}
          |  ${(i.component.dataports.map((d: Dataport) => StringTemplate.dataport(d)), "\n")}
          |  ${(i.component.binarySemaphores.map((b: BinarySemaphore) => s"has binary_semaphore ${b.name};"), "\n")}
          |  ${(i.component.semaphores.map((b: Semaphore) => s"has semaphore ${b.name};"), "\n")}
          |  ${(i.component.mutexes.map((m: Mutex) => s"has mutex ${m.name};"), "\n")}
          |  ${(i.component.externalEntities.map((s: String) => s), "\n")}
          |}
          """

    if(Util.isMonitor(name)) {
      add(s"${Util.DIR_COMPONENTS}/${Util.DIR_MONITORS}/${name}/${name}.camkes", st)
    } else {
      add(s"${Util.DIR_COMPONENTS}/${name}/${name}.camkes", st)
    }

    return None()
  }

  def visitProcedure(o: Procedure): Option[ST] = {
    var methods: ISZ[ST] = ISZ()
    for(m <- o.methods) {
      methods = methods :+ visitMethod(m).get
    }

    val st =
      st"""procedure ${o.name} {
          |  ${(o.includes.map((i: String) => s"include ${i};"), "\n")}
          |  ${(methods, "\n")}
          |};"""

    add(s"${Util.DIR_INTERFACES}/${o.name}.idl4", st)

    return None()
  }

  def visitMethod(o: Method) : Option[ST] = {
    var params: ISZ[ST] = ISZ()
    for(p <- o.parameters) {
      val dir: String = p.direction match {
        case Direction.In => "in"
        case Direction.Out => "out"
        case Direction.Refin => "refin"
      }
      params = params :+ st"""${dir} ${p.typ} ${p.name}"""
    }

    val retType: String = o.returnType match {
      case Some(r) => r
      case _ => "void"
    }

    val st = st"""${retType} ${o.name}(${(params, ",")});"""
    return Some(st)
  }

  def add(path: String, content: ST) : Unit = {
    resources = resources :+ Util.createResource(path, content, T)
  }

  def addExeResource(path: String, content: ST) : Unit = {
    resources = resources :+ Util.createExeResource(path, content, T)
  }

  def getHamrLib(instanceName: String, hamrLibs: Map[String, HamrLib]): Option[HamrLib] = {
    return if(hamrLibs.contains(instanceName)) hamrLibs.get(instanceName)
    else if(hamrLibs.contains(Util.SlangTypeLibrary)) hamrLibs.get(Util.SlangTypeLibrary)
    else None()
  }
}
