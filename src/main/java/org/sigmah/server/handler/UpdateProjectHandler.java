package org.sigmah.server.handler;

import java.util.Date;
import java.util.List;

import javax.persistence.NoResultException;
import javax.persistence.Query;
import javax.persistence.TypedQuery;

import org.sigmah.server.dispatch.impl.UserDispatch.UserExecutionContext;
import org.sigmah.server.domain.Country;
import org.sigmah.server.domain.HistoryToken;
import org.sigmah.server.domain.OrgUnit;
import org.sigmah.server.domain.Project;
import org.sigmah.server.domain.User;
import org.sigmah.server.domain.element.FlexibleElement;
import org.sigmah.server.domain.util.Deleteable;
import org.sigmah.server.domain.value.TripletValue;
import org.sigmah.server.domain.value.Value;
import org.sigmah.server.handler.base.AbstractCommandHandler;
import org.sigmah.server.mapper.Mapper;
import org.sigmah.server.service.UserPermissionPolicy;
import org.sigmah.shared.command.UpdateProject;
import org.sigmah.shared.command.result.VoidResult;
import org.sigmah.shared.dispatch.CommandException;
import org.sigmah.shared.dto.element.DefaultFlexibleElementDTO;
import org.sigmah.shared.dto.element.FlexibleElementDTO;
import org.sigmah.shared.dto.element.event.ValueEventWrapper;
import org.sigmah.shared.dto.referential.DefaultFlexibleElementType;
import org.sigmah.shared.dto.referential.ValueEventChangeType;
import org.sigmah.shared.dto.value.TripletValueDTO;
import org.sigmah.shared.util.ValueResultUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.persist.Transactional;

/**
 * Updates the values of the flexible elements for a specific project.
 * 
 * @author Raphaël Calabro (rcalabro@ideia.fr)
 */
public class UpdateProjectHandler extends AbstractCommandHandler<UpdateProject, VoidResult> {

	/**
	 * Logger.
	 */
	private final static Logger LOG = LoggerFactory.getLogger(UpdateProjectHandler.class);

	private final Mapper mapper;
	private final Injector injector;

	@Inject
	public UpdateProjectHandler(Mapper mapper, Injector injector) {
		this.mapper = mapper;
		this.injector = injector;
	}

	/**
	 * {@inheritDoc}
	 */
	@SuppressWarnings("unchecked")
	@Override
	public VoidResult execute(final UpdateProject cmd, final UserExecutionContext context) throws CommandException {

		User user = context.getUser();

		if (LOG.isDebugEnabled()) {
			LOG.debug("[execute] Updates project #" + cmd.getProjectId() + " with following values #" + cmd.getValues().size() + " : " + cmd.getValues());
		}

		// Search the given project.
		final Project project = em().find(Project.class, cmd.getProjectId());

		// This date must be the same for all the saved values !
		final Date historyDate = new Date();
		
		final Integer historableId = cmd.getProjectId();
		final List<ValueEventWrapper> values = cmd.getValues();
		final int projectId = cmd.getProjectId();
		
		updateProject(values, projectId, historableId, project, historyDate, user);

		return null;
	}

	@Transactional
	protected void updateProject(final List<ValueEventWrapper> values, final int projectId, final Integer historableId, final Project project, final Date historyDate, User user) throws CommandException {
		// Iterating over the value change events
		for (final ValueEventWrapper valueEvent : values) {
			
			// Event parameters.
			final FlexibleElementDTO source = valueEvent.getSourceElement();
			final FlexibleElement element = em().find(FlexibleElement.class, source.getId());
			final TripletValueDTO updateListValue = valueEvent.getListValue();
			final String updateSingleValue = valueEvent.getSingleValue();
			final boolean isProjectCountryChanged = valueEvent.isProjectCountryChanged();

			if (LOG.isDebugEnabled()) {
				LOG.debug("[execute] Updates value of element #" + source.getId() + " (" + source.getEntityName() + ')');
				LOG.debug("[execute] Event of type " + valueEvent.getChangeType() + " with value " + updateSingleValue + " and list value " + updateListValue + ".");
			}

			// Case of the default flexible element which values arent't stored
			// like other values. These values impact directly the project.
			if (source instanceof DefaultFlexibleElementDTO && !((DefaultFlexibleElementType.BUDGET.equals(((DefaultFlexibleElementDTO) source).getType())))) {

				final DefaultFlexibleElementDTO defaultElement = (DefaultFlexibleElementDTO) source;

				if (LOG.isDebugEnabled()) {
					LOG.debug("[execute] Default element case '" + defaultElement.getType() + "'.");
				}

				// Saves the value and switch to the next value.
				final String oldValue = saveDefaultElement(projectId, defaultElement.getType(), updateSingleValue, isProjectCountryChanged);

				// Checks if the first value as already been historized or not.
				List<HistoryToken> results = null;
				if (element != null) {
					final TypedQuery<HistoryToken> query =
						em().createQuery("SELECT h FROM HistoryToken h WHERE h.elementId = :elementId AND h.projectId = :projectId", HistoryToken.class);
					query.setParameter("elementId", element.getId());
					query.setParameter("projectId", historableId);
					results = query.getResultList();
				}

				if (results == null || results.isEmpty()) {
					final Date oldDate;
					final User oldOwner;
					if (project != null) {
						oldDate = project.getLastSchemaUpdate();
						oldOwner = project.getOwner();
					} else {
						oldDate = new Date(historyDate.getTime() - 1);
						oldOwner = null;
					}

					// Historize the first value.
					if (oldValue != null) {
						historize(oldDate, element, historableId, oldOwner, ValueEventChangeType.ADD, oldValue, null);
					}
				}

				// Historize the value.
				historize(historyDate, element, historableId, user, ValueEventChangeType.EDIT, updateSingleValue, null);

				continue;
			}

			// Retrieving the current value
			final Value currentValue = retrieveValue(projectId, source.getId(), user);

			// Unique value of the flexible element.
			if (updateListValue == null) {

				if (LOG.isDebugEnabled()) {
					LOG.debug("[execute] Basic value case.");
				}

				currentValue.setValue(updateSingleValue);

				// Historize the value.
				historize(historyDate, element, historableId, user, ValueEventChangeType.EDIT, updateSingleValue, null);
			}
			
			// Special case : this value is a part of a list which is the true value of the flexible element. (only used for
			// the TripletValue class for the moment)
			else {
				if (LOG.isDebugEnabled()) {
					LOG.debug("[execute] List value case.");
				}

				// The value of the element is a list of ids (default separated).
				final List<Integer> ids = ValueResultUtils.splitValuesAsInteger(currentValue.getValue());

				if (LOG.isDebugEnabled()) {
					LOG.debug("[execute] The current list of ids is : " + ids + ".");
				}

				// Cast the update value (as a DTO).
				final TripletValueDTO item = updateListValue;

				Class<TripletValue> clazz = TripletValue.class;

				try {
					// Computes the respective entity class name.
					clazz = (Class<TripletValue>) Class.forName(Project.class.getPackage().getName() + '.' + item.getEntityName());

				} catch (ClassNotFoundException e) {
					// Unable to find the entity class, the event is ignored.
					LOG.error("[execute] Unable to find the entity class : '" + item.getEntityName() + "'.");
					continue;
				}

				switch (valueEvent.getChangeType()) {
					case ADD:
						onAdd(item, clazz, ids, currentValue, historyDate, element, historableId, user);
						break;
						
					case REMOVE:
						if(!onDelete(item, clazz, ids, currentValue, historyDate, element, historableId, user)) {
							// Do not historize, the value hasn't been changed.
							continue;
						}
						break;
						
					case EDIT:
						onEdit(item, clazz, historyDate, element, historableId, user);
						break;
						
					default:
						LOG.debug("[execute] Unknown command " + valueEvent.getChangeType() + ".");
						break;
				}

				LOG.debug("[execute] The new list of ids is : " + ids + ".");
			}

			// Store the value.
			em().merge(currentValue);
		}

		// Update user permissions
		Project updatedProject = em().find(Project.class, projectId);
		if (updatedProject != null) {
			OrgUnit newOrgUnit = null;
			for (OrgUnit orgUnit : updatedProject.getPartners()) {
				newOrgUnit = orgUnit;
				break;
			}
			if (newOrgUnit != null) {
				final UserPermissionPolicy permissionPolicy = injector.getInstance(UserPermissionPolicy.class);
				permissionPolicy.deleteUserPemissionByProject(projectId);
				permissionPolicy.updateUserPermissionByOrgUnit(newOrgUnit);
			}
		}
	}

	protected void onAdd(final TripletValueDTO item, Class<TripletValue> clazz, final List<Integer> ids, final Value currentValue, final Date historyDate, final FlexibleElement element, final Integer historableId, User user) {
		LOG.debug("[execute] Adds an element to the list.");
		
		// Adds the element.
		TripletValue entity = mapper.map(item, clazz);
		entity = em().merge(entity);
		
		LOG.debug("[execute] Successfully create the entity with id #" + entity.getId() + ".");
		
		// Updates the value.
		ids.add(entity.getId());
		currentValue.setValue(ValueResultUtils.mergeElements(ids));
		
		// Historize the value.
		historize(historyDate, element, historableId, user, ValueEventChangeType.ADD, null, entity);
	}

	protected boolean onDelete(final TripletValueDTO item, Class<TripletValue> clazz, final List<Integer> ids, final Value currentValue, final Date historyDate, final FlexibleElement element, final Integer historableId, User user) {
		LOG.debug("[execute] Removes a element from the list.");

		// Retrieves the element.
		final TripletValue entity = em().find(clazz, item.getId());

		if(!(entity instanceof Deleteable)) {
			LOG.debug("[execute] The element isn't deletable, the event is ignored.");
			return false;
		}
		
		// Marks the entity as deleted.
		((Deleteable) entity).delete();
		em().merge(entity);
		
		if (LOG.isDebugEnabled()) {
			LOG.debug("[execute] Successfully remove the entity with id #" + entity.getId() + ".");
		}
		
		// Updates the value.
		ids.remove(entity.getId());
		currentValue.setValue(ValueResultUtils.mergeElements(ids));
		
		// Historize the value.
		historize(historyDate, element, historableId, user, ValueEventChangeType.REMOVE, null, entity);
		return true;
	}

	protected void onEdit(final TripletValueDTO item, Class<TripletValue> clazz, final Date historyDate, final FlexibleElement element, final Integer historableId, User user) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("[execute] Edits a element from the list.");
		}
		
		// Retrieves the element.
		final TripletValue entity = mapper.map(item, clazz);
		em().merge(entity);
		
		if (LOG.isDebugEnabled()) {
			LOG.debug("[execute] Successfully edit the entity with id #" + entity.getId() + ".");
		}
		
		// Historize the value.
		historize(historyDate, element, historableId, user, ValueEventChangeType.EDIT, null, entity);
	}

	private void historize(Date date, FlexibleElement element, Integer projectId, User user, ValueEventChangeType type, String singleValue, TripletValue listValue) {

		// Manages history.
		if (element != null && element.isHistorable()) {

			final HistoryToken historyToken = new HistoryToken();

			historyToken.setElementId(element.getId());
			historyToken.setProjectId(projectId);
			historyToken.setDate(date);
			historyToken.setUser(user);
			historyToken.setType(type);

			// Sets the value or list value.
			if (listValue == null) {
				historyToken.setValue(element.asHistoryToken(singleValue));
			} else {
				historyToken.setValue(element.asHistoryToken(listValue));
			}

			em().persist(historyToken);
		}
	}

	/**
	 * Retrieves the value for the given project and the given element. If there isn't yet a value, it will be created.
	 * 
	 * @param projectId
	 *          The project id.
	 * @param elementId
	 *          The source element id.
	 * @param user
	 *          The user which launch the command.
	 * @return The value.
	 */
	public Value retrieveValue(int projectId, Integer elementId, User user) {

		// Retrieving the current value
		final Query query = em().createQuery("SELECT v FROM Value v WHERE v.containerId = :projectId and v.element.id = :elementId");
		query.setParameter("projectId", projectId);
		query.setParameter("elementId", elementId);

		Value currentValue = null;

		try {
			currentValue = (Value) query.getSingleResult();
		} catch (NoResultException nre) {
			// No current value
		}

		// Update operation.
		if (currentValue != null) {

			if (LOG.isDebugEnabled()) {
				LOG.debug("[execute] Retrieves a value for element #" + elementId + ".");
			}

			currentValue.setLastModificationAction('U');
		}
		// Create operation
		else {

			if (LOG.isDebugEnabled()) {
				LOG.debug("[execute] Creates a value for element #" + elementId + ".");
			}

			currentValue = new Value();
			currentValue.setLastModificationAction('C');

			// Parent element
			final FlexibleElement element = em().find(FlexibleElement.class, elementId);
			currentValue.setElement(element);

			// Container
			currentValue.setContainerId(projectId);
		}

		// Updates the value's fields.
		currentValue.setLastModificationDate(new Date());
		currentValue.setLastModificationUser(user);

		return currentValue;
	}

	/**
	 * Updates the current project with the new value of a default element.
	 * 
	 * @param id
	 *          The project id.
	 * @param type
	 *          The type of the default element.
	 * @param value
	 *          The new value.
	 * @return The old value.
	 */
	private String saveDefaultElement(int id, DefaultFlexibleElementType type, String value, boolean isProjectCountryChanged) {

		// All default values are managed as strings.
		// See DefaultFlexibleElementDTO.getComponent();
		if (value == null) {
			LOG.error("[saveDefaultElement] The value isn't a string and cannot be considered.");
			return null;
		}

		final String stringValue = value;

		// Retrieves container.
		final Project project = em().find(Project.class, id);
		final OrgUnit orgUnit = em().find(OrgUnit.class, id);

		if (project == null && orgUnit == null) {
			LOG.error("[saveDefaultElement] Container with id '" + id + "' not found.");
			return null;
		}

		if (project != null) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("[saveDefaultElement] Found project with code '" + project.getName() + "'.");
			}
		} else {
			if (LOG.isDebugEnabled()) {
				LOG.debug("[saveDefaultElement] Found org unit with code '" + orgUnit.getName() + "'.");
			}
		}

		final String oldValue;

		switch (type) {
			case CODE:

				if (project != null) {
					oldValue = project.getName();
					project.setName(stringValue);
				} else {
					oldValue = orgUnit.getName();
					orgUnit.setName(stringValue);
				}

				if (LOG.isDebugEnabled()) {
					LOG.debug("[saveDefaultElement] Set container code to '" + stringValue + "'.");
				}
				break;
			case TITLE:

				if (project != null) {
					oldValue = project.getFullName();
					project.setFullName(stringValue);
				} else {
					oldValue = orgUnit.getFullName();
					orgUnit.setFullName(stringValue);
				}

				if (LOG.isDebugEnabled()) {
					LOG.debug("[saveDefaultElement] Set container full name to '" + stringValue + "'.");
				}
				break;
			case START_DATE: {

				// Decodes timestamp.
				if (project != null) {
					oldValue = project.getStartDate() == null ? null : String.valueOf(project.getStartDate().getTime());
					if ("".equals(stringValue)) {

						project.setStartDate(null);

						if (LOG.isDebugEnabled()) {
							LOG.debug("[saveDefaultElement] Set container start date to null.");
						}
					} else {

						final long timestamp = Long.valueOf(stringValue);
						final Date date = new Date(timestamp);
						project.setStartDate(date);

						if (LOG.isDebugEnabled()) {
							LOG.debug("[saveDefaultElement] Set container start date to '" + date + "'.");
						}
					}
				} else {
					oldValue = null;
				}
			}
				break;
			case END_DATE: {

				// Decodes timestamp.
				if (project != null) {
					oldValue = project.getEndDate() == null ? null : String.valueOf(project.getEndDate().getTime());
					if ("".equals(stringValue)) {

						project.setEndDate(null);

						if (LOG.isDebugEnabled()) {
							LOG.debug("[saveDefaultElement] Set container end date to null.");
						}
					} else {

						final long timestamp = Long.valueOf(stringValue);
						final Date date = new Date(timestamp);
						project.setEndDate(date);

						if (LOG.isDebugEnabled()) {
							LOG.debug("[saveDefaultElement] Set container end date to '" + date + "'.");
						}
					}
				} else {
					oldValue = null;
				}
			}
				break;
			case COUNTRY: {

				if (orgUnit != null) {
					if (orgUnit.getOfficeLocationCountry() != null) {
						oldValue = String.valueOf(orgUnit.getOfficeLocationCountry().getId());
					} else {
						oldValue = null;
					}

					// Retrieves country.
					final Country country = em().find(Country.class, Integer.valueOf(stringValue));
					orgUnit.setOfficeLocationCountry(country);

					if (LOG.isDebugEnabled()) {
						LOG.debug("[saveDefaultElement] Set container country to '" + country.getName() + "'.");
					}
				} else {
					oldValue = null;
				}
			}
				break;
			case MANAGER: {

				if (project != null) {
					oldValue = project.getManager() == null ? null : String.valueOf(project.getManager().getId());

					// Retrieves manager.
					final User manager = em().find(User.class, Integer.valueOf(stringValue));
					project.setManager(manager);

					if (LOG.isDebugEnabled()) {
						LOG.debug("[saveDefaultElement] Set container manager to '" + manager.getName() + "'.");
					}

				} else {
					oldValue = null;
				}
			}
				break;
			case ORG_UNIT: {

				if (project != null) {

					OrgUnit old = null;
					for (OrgUnit p : project.getPartners()) {
						old = p;
						break;
					}

					oldValue = old == null ? null : String.valueOf(old.getId());

					// Retrieves manager.
					final OrgUnit o = em().find(OrgUnit.class, Integer.valueOf(stringValue));
					project.getPartners().clear();
					project.getPartners().add(o);

					if (isProjectCountryChanged) {
						LOG.debug("Changing country is true.");
						project.setCountry(o.getOfficeLocationCountry());
					} else {
						LOG.debug("Changing country is false.");
					}

					if (LOG.isDebugEnabled()) {
						LOG.debug("[saveDefaultElement] Set container org unit to '" + o.getFullName() + "'.");
					}

				} else {
					oldValue = null;
				}
			}
				break;
			default:
				LOG.error("[saveDefaultElement] Unknown type '" + type + "' for the default flexible elements.");
				return null;
		}

		// Updates container.
		if (project != null) {
			em().merge(project);
		} else {
			em().merge(orgUnit);
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("[saveDefaultElement] Updates the container.");
		}

		return oldValue;
	}
}