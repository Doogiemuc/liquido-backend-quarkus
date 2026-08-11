package org.liquido.polly;

import com.fasterxml.jackson.annotation.JsonBackReference;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * One option in a polly - the polly equivalent of a {@link org.liquido.poll.ProposalEntity}.
 *
 * <p>Deliberately tiny. A team poll's proposal carries a description, an icon, supporters,
 * likes, a status and a creator; a polly option is a line of text and a position in the list.
 * That is the whole "duplication" between the two products, and it is plumbing rather than
 * logic.
 *
 * <p>Extends {@link PanacheEntity} rather than {@code LiquidoBaseEntity}: the latter brings a
 * {@code @CreatedBy UserEntity}, and a polly has no LIQUIDO user behind it at all.
 */
@Data
@NoArgsConstructor(force = true)
@EqualsAndHashCode(of = {"title"}, callSuper = true)
@Entity
@Table(name = "polly_proposal")
public class PollyProposalEntity extends PanacheEntity {

	/** The polly this option belongs to. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "polly_id", nullable = false)
	@JsonBackReference
	public PollyEntity polly;

	/** The option as the creator typed it. */
	@NotNull
	@Column(nullable = false)
	public String title;

	/**
	 * Position in the list, starting at 0.
	 * <p>Also the deterministic tie-break when Ranked Pairs produces more than one winner:
	 * the option listed first wins. See {@code PollyService.determineWinner}.
	 */
	@Column(name = "sort_order", nullable = false)
	public int sortOrder;

	public PollyProposalEntity(PollyEntity polly, String title, int sortOrder) {
		this.polly = polly;
		this.title = title;
		this.sortOrder = sortOrder;
	}

	@Override
	public String toString() {
		return "PollyProposal[id=" + id + ", sortOrder=" + sortOrder + ", title='" + title + "']";
	}
}
