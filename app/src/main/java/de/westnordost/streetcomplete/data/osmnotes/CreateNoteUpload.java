package de.westnordost.streetcomplete.data.osmnotes;

import android.util.Log;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;

import de.westnordost.streetcomplete.data.QuestStatus;
import de.westnordost.osmapi.common.SingleElementHandler;
import de.westnordost.osmapi.common.errors.OsmConflictException;
import de.westnordost.osmapi.map.MapDataDao;
import de.westnordost.osmapi.map.data.BoundingBox;
import de.westnordost.osmapi.map.data.Element;
import de.westnordost.osmapi.notes.Note;
import de.westnordost.osmapi.notes.NotesDao;

// TODO test case
public class CreateNoteUpload
{
	private static final String TAG = "CreateNoteUpload";

	private final NotesDao osmDao;
	private final CreateNoteDao createNoteDB;
	private final NoteDao noteDB;
	private final OsmNoteQuestDao noteQuestDB;
	private final MapDataDao mapDataDao;

	@Inject public CreateNoteUpload(
			CreateNoteDao createNoteDB, NotesDao osmDao, NoteDao noteDB,
			OsmNoteQuestDao noteQuestDB, MapDataDao mapDataDao)
	{
		this.createNoteDB = createNoteDB;
		this.noteQuestDB = noteQuestDB;
		this.noteDB = noteDB;
		this.osmDao = osmDao;
		this.mapDataDao = mapDataDao;
	}

	public void upload(AtomicBoolean cancelState)
	{
		int created = 0, obsolete = 0;
		for(CreateNote createNote : createNoteDB.getAll(null))
		{
			if(cancelState.get()) break;

			if(uploadCreateNote(createNote) != null)
			{
				created++;
			}
			else
			{
				obsolete++;
			}
		}
		String logMsg = "Created " + created + " notes";
		if(obsolete > 0)
		{
			logMsg += " but dropped " + obsolete + " because they were obsolete already";
		}
		Log.i(TAG, logMsg);
	}

	private Note uploadCreateNote(CreateNote n)
	{
		if(isAssociatedElementDeleted(n))
		{
			Log.i(TAG, "Dropped to be created note " + getCreateNoteStringForLog(n) +
					" because the associated element has already been deleted");
			createNoteDB.delete(n.id);
			return null;
		}

		Note newNote = createOrCommentNote(n);

		if (newNote != null)
		{
			// add a hidden quest as a blocker so that at this location no quests are created.
			// if the note was not added, don't do this (see below) -> probably based on old data
			OsmNoteQuest noteQuest = new OsmNoteQuest(newNote);
			noteQuest.setStatus(QuestStatus.HIDDEN);
			noteDB.put(newNote);
			noteQuestDB.add(noteQuest);
		}
		else
		{
			Log.i(TAG, "Dropped a to be created note " + getCreateNoteStringForLog(n) +
					" because a note with the same associated element has already been closed");
			// so the problem has likely been solved by another mapper
		}

		createNoteDB.delete(n.id);
		return newNote;
	}

	private static String getCreateNoteStringForLog(CreateNote n)
	{
		return "\"" + n.text + "\" at " + n.position.getLatitude() + ", " + n.position.getLongitude();
	}

	private boolean isAssociatedElementDeleted(CreateNote n)
	{
		return n.hasAssociatedElement() && retrieveElement(n) == null;
	}

	private Element retrieveElement(CreateNote n)
	{
		switch(n.elementType)
		{
			case NODE: return mapDataDao.getNode(n.elementId);
			case WAY:  return mapDataDao.getWay(n.elementId);
			case RELATION: return mapDataDao.getRelation(n.elementId);
		}
		return null;
	}

	/** Create a note at the given position, or, if there is already a note at the exact same
	 *  position AND its associated element is the same, adds the user's message as another comment.
	 *
	 *  Returns null and does not add the note comment if that note has already been closed because
	 *  the contribution is very likely obsolete (based on old data)*/
	private Note createOrCommentNote(CreateNote n)
	{
		if(n.hasAssociatedElement())
		{
			Note oldNote = findAlreadyExistingNoteWithSameAssociatedElement(n);

			if(oldNote != null)
			{
				if(oldNote.isOpen())
				{
					try
					{
						return osmDao.comment(oldNote.id, n.text);
					}
					catch (OsmConflictException e)
					{
						return null;
					}
				}
				else
				{
					return null;
				}
			}
		}
		return osmDao.create(n.position, n.text + getAssociatedElementString(n));
	}

	private Note findAlreadyExistingNoteWithSameAssociatedElement(final CreateNote newNote)
	{
		SingleElementHandler<Note> handler = new SingleElementHandler<Note>()
		{
			@Override public void handle(Note oldNote)
			{
				if(newNote.hasAssociatedElement())
				{
					String firstCommentText = oldNote.comments.get(0).text;

					if(firstCommentText.matches(getAssociatedElementRegex(newNote)))
					{
						super.handle(oldNote);
					}
				}
			}
		};
		final int hideClosedNoteAfter = 7;
		osmDao.getAll(new BoundingBox(
				newNote.position.getLatitude(), newNote.position.getLongitude(),
				newNote.position.getLatitude(), newNote.position.getLongitude()
		), handler, 10, hideClosedNoteAfter);
		return handler.get();
	}

	private static String getAssociatedElementRegex(CreateNote n)
	{
		String elementType = n.elementType.name();
		return "(?i:.*"+elementType+"\\s*#"+n.elementId+".*)";
	}

	private static String getAssociatedElementString(CreateNote n)
	{
		return " (" + n.elementType.name().toLowerCase(Locale.UK) + " #" + n.elementId + " )";
	}
}