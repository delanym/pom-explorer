package fr.lteconsulting.pomexplorer;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jgrapht.Graph;

import fr.lteconsulting.autothreaded.AutoThreaded;
import fr.lteconsulting.pomexplorer.graph.PomGraph.PomGraphReadTransaction;
import fr.lteconsulting.pomexplorer.graph.relation.Relation;
import fr.lteconsulting.pomexplorer.model.Gav;
import fr.lteconsulting.pomexplorer.webserver.MessageFactory;

@AutoThreaded
public class Builder
{
	private final String talkId = MessageFactory.newGuid();

	private final String pipelineStatusTalkId = "buildPipelineStatus";

	private ApplicationSession session;

	private final Set<Project> projectsToBuild = new HashSet<>();

	private final Set<Project> projectsToBuildForced = new HashSet<>();

	private Project lastChangedProject;

	private final Set<Project> erroredProjects = new HashSet<>();

	public void setSession( ApplicationSession session )
	{
		this.session = session;
	}

	public void clearJobs()
	{
		projectsToBuild.clear();
		projectsToBuildForced.clear();

		printBuildPipelineState( null );
	}

	public void buildProject( Project project, Log log )
	{
		if( !project.isBuildable() )
		{
			log.html( Tools.errorMessage( "cannot build non buildable project " + project ) );
			return;
		}

		projectsToBuildForced.add( project );

		printBuildPipelineState( null );
	}

	public void buildAll()
	{
		projectsToBuild.addAll( session.projectsWatcher().watchedProjects() );

		printBuildPipelineState( null );
	}

	protected void onEmptyMessageQueue()
	{
		step();
	}

	private void step()
	{
		try
		{
			Thread.sleep( 1000 );
		}
		catch( InterruptedException e )
		{
			e.printStackTrace();
		}

		if( session == null )
			return;

		Project changed = session.projectsWatcher().hasChanged();
		if( changed != null )
		{
			erroredProjects.remove( changed );
			lastChangedProject = changed;
			processProjectChange( session, changed );

			printBuildPipelineState( null );

			return;
		}

		Project toBuild = findProjectToBuild();
		if( toBuild != null )
		{
			printBuildPipelineState( toBuild );

			boolean success = build( toBuild );

			if( success )
			{
				success( "build succesful for project " + toBuild.getGav() + " : " + toBuild );
				erroredProjects.remove( toBuild );
			}
			else
			{
				erroredProjects.add( toBuild );

				error( "error building "
						+ toBuild
						+ " !<br/>this project and dependent ones are going to be removed from the build list.<br/>fix the problem which prevent the build to success and the build will restart automatically..." );
				dependentsAndSelf( toBuild.getGav() ).stream().map( g -> session.projects().forGav( g ) ).filter( p -> p != null ).forEach( p -> projectsToBuild.remove( p ) );
			}

			printBuildPipelineState( null );
		}
	}

	private void printBuildPipelineState( Project projectBuilding )
	{
		PomGraphReadTransaction tx = session.graph().read();

		try
		{
			List<Gav> gavs = buildOrder( tx.internalGraph() );

			StringBuilder sb = new StringBuilder();

			sb.append( "<br/>" );
			sb.append( "build pipeline state:<br/>" );
			for( Gav gav : gavs )
			{
				Project project = session.projects().forGav( gav );
				if( project != null && (inDependenciesOfMaintainedProjects( project ) || projectsToBuildForced.contains( project )) )
				{
					sb.append( "<span class='" + (project == lastChangedProject ? "refreshedProject " : "") + (projectsToBuildForced.contains( project ) ? "BUILD FORCED " : "")
							+ (projectsToBuild.contains( project ) ? "toBuildProject " : "") + (projectBuilding == project ? "buildingProject " : "")
							+ (erroredProjects.contains( project ) ? "errorProject " : "") + (session.maintainedProjects().contains( project ) ? "maintainedProject " : "") + "'>"
							+ project.getGav() + (session.maintainedProjects().contains( project ) ? " [maintained]" : "") + (projectBuilding == project ? " [building]" : "")
							+ (projectsToBuild.contains( project ) ? " [build waiting...]" : "") + (erroredProjects.contains( project ) ? " [project in error]" : "")
							+ "</span><br/>" );
				}
			}
			sb.append( "<br/>" );

			logBuildPipeline( sb.toString() );
		}
		catch( Exception e )
		{
			logBuildPipeline( "error: " + e );
		}
	}

	/**
	 * Find the first project to be built in the graph's topological order
	 * 
	 * @return the project or null
	 */
	private Project findProjectToBuild()
	{
		PomGraphReadTransaction tx = session.graph().read();

		try
		{
			List<Gav> gavs = buildOrder( tx.internalGraph() );

			for( Gav gav : gavs )
			{
				Project project = session.projects().forGav( gav );
				if( project != null && (!erroredProjects.contains( project ))
						&& (projectsToBuildForced.contains( project ) || (projectsToBuild.contains( project ) && inDependenciesOfMaintainedProjects( project ))) )
				{
					projectsToBuild.remove( project );
					projectsToBuildForced.remove( project );
					return project;
				}
			}
		}
		catch( Exception e )
		{
			System.err.println( "error in Builder thread :" );
			e.printStackTrace();
		}

		return null;
	}

	/**
	 * Computes a build order (dependencies first). Contrary to jgrapht's
	 * {@code TopologicalOrderIterator}, this tolerates cycles in the graph, which can
	 * legitimately happen in a POM graph.
	 */
	private static List<Gav> buildOrder( Graph<Gav, Relation> graph )
	{
		List<Gav> order = topologicalOrder( graph );
		Collections.reverse( order );
		return order;
	}

	/**
	 * Kahn's algorithm. When no vertex is left with a zero in-degree but vertices remain,
	 * those vertices are part of a cycle : one of them is picked to break the cycle.
	 */
	private static List<Gav> topologicalOrder( Graph<Gav, Relation> graph )
	{
		Map<Gav, Integer> inDegrees = new HashMap<>();
		Deque<Gav> ready = new ArrayDeque<>();
		Set<Gav> remaining = new HashSet<>( graph.vertexSet() );

		for( Gav gav : graph.vertexSet() )
		{
			int inDegree = graph.inDegreeOf( gav );
			inDegrees.put( gav, inDegree );
			if( inDegree == 0 )
				ready.add( gav );
		}

		List<Gav> order = new ArrayList<>( graph.vertexSet().size() );

		while( !remaining.isEmpty() )
		{
			Gav next;
			if( !ready.isEmpty() )
			{
				next = ready.poll();
				if( !remaining.remove( next ) )
					continue;
			}
			else
			{
				// remaining vertices belong to a cycle : break it by picking one of them
				next = remaining.iterator().next();
				remaining.remove( next );
			}

			order.add( next );

			for( Relation relation : graph.outgoingEdgesOf( next ) )
			{
				Gav target = graph.getEdgeTarget( relation );
				if( remaining.contains( target ) )
				{
					int inDegree = inDegrees.merge( target, -1, Integer::sum );
					if( inDegree <= 0 )
						ready.add( target );
				}
			}
		}

		return order;
	}

	private void processProjectChange( ApplicationSession session, Project project )
	{
		if( project == null || projectsToBuild.contains( project ) )
			return;

		log( "project " + project + " has been modified, appending to build list..." );

		for( Gav gav : dependentsAndSelf( project.getGav() ) )
		{
			Project p = session.projects().forGav( gav );
			if( p != null && p.isBuildable() )
			{
				if( !inDependenciesOfMaintainedProjects( p ) )
					continue;
				log( "add to build list : " + p.getGav() );
				projectsToBuild.add( p );
			}
		}
	}

	private boolean inDependenciesOfMaintainedProjects( Project project )
	{
		if( project == null )
			return false;

		Set<Gav> possibleGavs = new HashSet<>();
		for( Project p : session.maintainedProjects() )
			possibleGavs.addAll( dependenciesAndSelf( p.getGav() ) );

		boolean res = possibleGavs.contains( project.getGav() );
		return res;
	}

	private Set<Gav> dependenciesAndSelf( Gav gav )
	{
		PomGraphReadTransaction tx = session.graph().read();

		HashSet<Gav> res = new HashSet<>();
		res.add( gav );
		tx.relationsRec( gav ).stream().forEach( r -> res.add( tx.targetOf( r ) ) );
		return res;
	}

	private Set<Gav> dependentsAndSelf( Gav gav )
	{
		PomGraphReadTransaction tx = session.graph().read();
		HashSet<Gav> res = new HashSet<>();
		res.add( gav );
		tx.relationsReverseRec( gav ).stream().forEach( r -> res.add( tx.sourceOf( r ) ) );
		return res;
	}

	private boolean build( Project project )
	{
		boolean demo = false;

		if( demo )
		{
			log( "building " + project + "..." );
			File directory = project.getPomFile().getParentFile();
			log( "cd " + directory + "<br/>" );
			log( "mvn install -N -DskipTests<br/>" );
			try
			{
				Thread.sleep( 500 );
			}
			catch( InterruptedException e )
			{
			}
			log( project + " build done" );
			return true;
		}
		else
		{
			MavenBuildTaskAutoThreaded builder = new MavenBuildTaskAutoThreaded();
			boolean res = builder.build( session, project, talkId );
			builder.stop();
			return res;
		}
	}

	private void log( String message )
	{
		message = Tools.buildMessage( message );
		for( Client client : session.getClients() )
			client.sendHtml( talkId, message );
	}

	private void logBuildPipeline( String message )
	{
		message = Tools.buildMessage( message );
		for( Client client : session.getClients() )
			client.sendHtml( pipelineStatusTalkId, message );
	}

	private void error( String message )
	{
		message = Tools.errorMessage( message );
		for( Client client : session.getClients() )
			client.sendHtml( talkId, message );
	}

	private void success( String message )
	{
		message = Tools.successMessage( message );
		for( Client client : session.getClients() )
			client.sendHtml( talkId, message );
	}
}
